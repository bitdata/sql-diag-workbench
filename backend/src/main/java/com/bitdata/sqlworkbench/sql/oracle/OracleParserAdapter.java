package com.bitdata.sqlworkbench.sql.oracle;

import com.bitdata.sqlworkbench.sql.SqlSerializationException;
import org.apache.calcite.sql.*;
import org.apache.calcite.sql.parser.*;
import org.apache.calcite.sql.parser.impl.*;
import org.apache.calcite.sql.util.SqlShuttle;
import java.io.*;
import java.util.*;

/** Finite Oracle CAST and national literal adapter, preserving source positions and explicit refusals. */
public final class OracleParserAdapter {
  public static final SqlParserImplFactory FACTORY=Parser::new;
  record Token(String text,int start,int end,boolean word){}
  record Declaration(String name,List<String> arguments,String lengthSemantics,int start,int end){}
  record National(String text,int start,int literalStart,int end){}
  record Prepared(String text,List<Declaration> declarations,List<Integer> lines,List<National> nationals){}
  public static final class NationalLiteral extends SqlCharStringLiteral {
    final String text;
    NationalLiteral(String text,SqlParserPos position){super(new org.apache.calcite.util.NlsString(text,"UTF-16LE",null),position);this.text=text;}
    public String text(){return text;}
    @Override public void unparse(SqlWriter writer,int l,int r){writer.literal("N'"+text.replace("'","''")+"'");}
    @Override public SqlCharStringLiteral clone(SqlParserPos pos){return new NationalLiteral(text,pos);}
  }
  public static final class NativeType extends SqlUserDefinedTypeNameSpec {
    final Declaration declaration;
    NativeType(Declaration d,SqlParserPos pos){super(d.name(),pos);declaration=d;}
    public String nativeName(){return declaration.name();}
    public List<String> arguments(){var args=new ArrayList<>(declaration.arguments());if(!declaration.lengthSemantics().isEmpty())args.add(declaration.lengthSemantics());return List.copyOf(args);}
    @Override public void unparse(SqlWriter w,int left,int right){
      w.keyword(nativeName());
      if(!declaration.arguments().isEmpty()){
        var f=w.startList("(",")");for(var arg:declaration.arguments()){w.sep(",");w.literal(arg);}
        if(!declaration.lengthSemantics().isEmpty())w.keyword(declaration.lengthSemantics());w.endList(f);
      }
    }
    @Override public boolean equalsDeep(SqlTypeNameSpec other,org.apache.calcite.util.Litmus litmus){return other instanceof NativeType n&&nativeName().equals(n.nativeName())&&arguments().equals(n.arguments())?litmus.succeed():litmus.fail("Oracle native declaration differs");}
    @Override public org.apache.calcite.rel.type.RelDataType deriveType(org.apache.calcite.sql.validate.SqlValidator validator){throw failure("ORACLE_TYPE_DERIVATION_UNVERIFIED");}
  }
  static SqlSerializationException failure(String reason){return new SqlSerializationException(reason);}
  static Prepared prepare(String sql){
    var tokens=new ArrayList<Token>();var lines=new ArrayList<Integer>();lines.add(0);
    for(int i=0;i<sql.length();i++)if(sql.charAt(i)=='\n')lines.add(i+1);
    for(int i=0;i<sql.length();){
      int begin=i;char c=sql.charAt(i);
      if(Character.isWhitespace(c)){i++;continue;}
      if(sql.startsWith("--",i)){while(i<sql.length()&&sql.charAt(i)!='\n')i++;continue;}
      if(sql.startsWith("/*",i)){int end=sql.indexOf("*/",i+2);if(end<0)throw failure("ORACLE_COMMENT_UNTERMINATED");i=end+2;continue;}
      if(c=='\''||c=='"'){
        char quote=c;i++;boolean closed=false;
        while(i<sql.length()){if(sql.charAt(i++)==quote){if(i<sql.length()&&sql.charAt(i)==quote)i++;else{closed=true;break;}}}
        if(!closed)throw failure("ORACLE_QUOTE_UNTERMINATED");
        tokens.add(new Token(sql.substring(begin,i),begin,i,false));continue;
      }
      if(Character.isLetter(c)||c=='_'||c=='$'||c=='#'){
        i++;while(i<sql.length()&&(Character.isLetterOrDigit(sql.charAt(i))||"_$#".indexOf(sql.charAt(i))>=0))i++;
        tokens.add(new Token(sql.substring(begin,i).toUpperCase(Locale.ROOT),begin,i,true));continue;
      }
      if(Character.isDigit(c)){i++;while(i<sql.length()&&Character.isDigit(sql.charAt(i)))i++;tokens.add(new Token(sql.substring(begin,i),begin,i,false));continue;}
      tokens.add(new Token(Character.toString(c),begin,++i,false));
    }
    for(int i=1;i+1<tokens.size();i++)if(tokens.get(i).text().equals(".")&&tokens.get(i+1).word()&&Set.of("NEXTVAL","CURRVAL").contains(tokens.get(i+1).text()))throw failure("ORACLE_SEQUENCE_REFERENCE_UNSUPPORTED");
    char[] masked=sql.toCharArray();var declarations=new ArrayList<Declaration>();
    var nationals=new ArrayList<National>();
    for(int i=0;i+1<tokens.size();i++){
      var n=tokens.get(i);var literal=tokens.get(i+1);
      if(n.word()&&n.text().equals("N")&&n.end()==literal.start()&&literal.text().startsWith("'")){
        masked[n.start()]=' ';
        nationals.add(new National(literal.text().substring(1,literal.text().length()-1).replace("''","'"),n.start(),literal.start(),literal.end()));
      }
    }
    for(int i=0;i+1<tokens.size();i++){
      if(!tokens.get(i).word()||!tokens.get(i).text().equals("CAST")||!tokens.get(i+1).text().equals("(")||i>0&&tokens.get(i-1).text().equals("."))continue;
      int depth=0;
      for(int j=i+2;j<tokens.size();j++){
        var t=tokens.get(j);
        if(t.text().equals("("))depth++;
        else if(t.text().equals(")")){if(depth--==0)break;}
        else if(depth==0&&t.word()&&t.text().equals("AS")){
          int end=j+1,inner=0;
          for(;end<tokens.size();end++){if(tokens.get(end).text().equals("("))inner++;else if(tokens.get(end).text().equals(")")&&inner--==0)break;}
          capture(tokens,j+1,end,masked,declarations);break;
        }
      }
    }
    return new Prepared(new String(masked),List.copyOf(declarations),lines,List.copyOf(nationals));
  }
  static void capture(List<Token> tokens,int first,int last,char[] masked,List<Declaration> declarations){
    if(first>=last||!tokens.get(first).word())return;
    String name=tokens.get(first).text();if(!Set.of("NUMBER","VARCHAR2","NVARCHAR2").contains(name))return;
    var args=new ArrayList<String>();String semantics="";int i=first+1;
    if(i<last){
      if(!tokens.get(i++).text().equals("("))throw failure("ORACLE_TYPE_DECLARATION_UNSUPPORTED");
      boolean value=true;
      while(i<last-1){
        var token=tokens.get(i++);
        if(!value){
          if(token.word()&&Set.of("BYTE","CHAR").contains(token.text())&&i==last-1){semantics=token.text();break;}
          if(!token.text().equals(","))throw failure("ORACLE_TYPE_ARGUMENT_UNSUPPORTED");value=true;continue;
        }
        String sign="";if(token.text().equals("-")){sign="-";if(i>=last-1)throw failure("ORACLE_TYPE_ARGUMENT_UNSUPPORTED");token=tokens.get(i++);}
        if(!token.text().matches("[0-9]+"))throw failure("ORACLE_TYPE_ARGUMENT_UNSUPPORTED");
        try{args.add(Integer.toString(Integer.parseInt(sign+token.text())));}catch(NumberFormatException e){throw failure("ORACLE_TYPE_ARGUMENT_RANGE_UNSUPPORTED");}
        value=false;
      }
      if(value||i!=last-1||!tokens.get(i).text().equals(")"))throw failure("ORACLE_TYPE_DECLARATION_UNSUPPORTED");
    }
    if(name.equals("NUMBER")){
      if(!semantics.isEmpty()||args.size()>2)throw failure("ORACLE_NUMBER_ARGUMENTS_UNSUPPORTED");
      if(!args.isEmpty()&&(Integer.parseInt(args.get(0))<1||Integer.parseInt(args.get(0))>38))throw failure("ORACLE_NUMBER_PRECISION_UNSUPPORTED");
      if(args.size()==2&&(Integer.parseInt(args.get(1))< -84||Integer.parseInt(args.get(1))>127))throw failure("ORACLE_NUMBER_SCALE_UNSUPPORTED");
    }else{
      int max=name.equals("NVARCHAR2")?2000:4000;
      if(args.size()!=1||Integer.parseInt(args.get(0))<1||Integer.parseInt(args.get(0))>max||name.equals("NVARCHAR2")&&!semantics.isEmpty())throw failure("ORACLE_CHARACTER_DECLARATION_UNSUPPORTED");
    }
    int start=tokens.get(first).start(),end=tokens.get(last-1).end();
    declarations.add(new Declaration(name,List.copyOf(args),semantics,start,end));
    for(int k=start;k<end;k++)if(masked[k]!='\r'&&masked[k]!='\n')masked[k]=' ';
    masked[start]='I';masked[start+1]='N';masked[start+2]='T';
  }
  static SqlNode restore(SqlNode root,Prepared prepared){
    var seen=new HashSet<Integer>();
    var result=root.accept(new SqlShuttle(){
      SqlParserPos enclosingPosition(SqlParserPos position) {
        if(position.getLineNum()<1||position.getLineNum()>prepared.lines().size())return position;
        int offset=prepared.lines().get(position.getLineNum()-1)+position.getColumnNum()-1;
        for(var n:prepared.nationals())if(n.literalStart()==offset)
          return new SqlParserPos(position.getLineNum(),position.getColumnNum()-1,position.getEndLineNum(),position.getEndColumnNum());
        return position;
      }
      @Override public SqlNode visit(SqlNodeList list) {
        SqlNode restored=super.visit(list);var position=enclosingPosition(list.getParserPosition());
        return position.equals(list.getParserPosition())?restored:restored.clone(position);
      }
      @Override public SqlNode visit(SqlCall call) {
        SqlNode restored=super.visit(call);var position=enclosingPosition(call.getParserPosition());
        return position.equals(call.getParserPosition())?restored:restored.clone(position);
      }
      @Override public SqlNode visit(SqlLiteral literal){
        var pos=literal.getParserPosition();if(pos.getLineNum()<1||pos.getLineNum()>prepared.lines().size())return literal;int offset=prepared.lines().get(pos.getLineNum()-1)+pos.getColumnNum()-1;
        for(var n:prepared.nationals())if(n.literalStart()==offset){
          seen.add(n.start());return new NationalLiteral(n.text(),new SqlParserPos(pos.getLineNum(),pos.getColumnNum()-1,pos.getEndLineNum(),pos.getEndColumnNum()));
        }
        return literal;
      }
      @Override public SqlNode visit(SqlDataTypeSpec spec){
      int line=spec.getParserPosition().getLineNum()-1;if(line<0||line>=prepared.lines().size())return spec;
      int offset=prepared.lines().get(line)+spec.getParserPosition().getColumnNum()-1;
      for(var d:prepared.declarations())if(d.start()==offset){seen.add(offset);return new SqlDataTypeSpec(new NativeType(d,spec.getParserPosition()),spec.getTimeZone(),spec.getNullable(),spec.getParserPosition());}
      return spec;
    }});
    if(seen.size()!=prepared.declarations().size()+prepared.nationals().size())throw failure("ORACLE_TYPE_LOCATION_UNVERIFIED");return result;
  }
  static final class Parser extends SqlParserImpl {
    Prepared prepared;
    Parser(Reader reader){super(new StringReader(""));ReInit(reader);}
    @Override public void ReInit(Reader reader){
      try{var text=new StringBuilder();char[] chars=new char[4096];for(int n;(n=reader.read(chars))!=-1;)text.append(chars,0,n);prepared=prepare(text.toString());
        var stream=new SimpleCharStream(new StringReader(prepared.text())){{setTabSize(1);}};
        super.ReInit(new SqlParserImplTokenManager(stream));
      }catch(IOException e){throw new UncheckedIOException(e);}
    }
    @Override public SqlNode parseSqlStmtEof()throws Exception{return restore(super.parseSqlStmtEof(),prepared);}
    @Override public SqlNode parseSqlExpressionEof()throws Exception{return restore(super.parseSqlExpressionEof(),prepared);}
  }
}
