package com.bitdata.sqlworkbench.sql.tsql;

import java.util.*;
import org.apache.calcite.sql.*;
import org.apache.calcite.sql.parser.SqlParserPos;
import org.apache.calcite.sql.util.SqlShuttle;

/** OPENJSON table-source AST adapter; no physical lineage or type certification. */
final class TsqlOpenJsonAdapter {
  record Declaration(int start,int schemaStart,int end,SqlNodeList columns,boolean apply){}
  static final SqlSpecialOperator COLUMN=new SqlSpecialOperator("M3_OPENJSON_COLUMN",SqlKind.OTHER){
    @Override public void unparse(SqlWriter w,SqlCall c,int l,int r){
      c.operand(0).unparse(w,0,0);c.operand(1).unparse(w,0,0);
      if(c.operand(2)!=null)c.operand(2).unparse(w,0,0);
      if(((SqlLiteral)c.operand(3)).booleanValue())w.keyword("AS JSON");
    }
  };
  static final SqlSpecialOperator ROW_SOURCE=new SqlSpecialOperator("M3_OPENJSON_ROW_SOURCE",SqlKind.COLLECTION_TABLE,200){
    @Override public org.apache.calcite.rel.type.RelDataType deriveType(org.apache.calcite.sql.validate.SqlValidator v,org.apache.calcite.sql.validate.SqlValidatorScope s,SqlCall c){throw new TsqlParserSupport.Unsupported("OPENJSON_ROW_TYPE_VALIDATION_UNIMPLEMENTED");}
    @Override public void unparse(SqlWriter w,SqlCall c,int l,int r){
      var f=w.startFunCall("OPENJSON");for(var a:((SqlNodeList)c.operand(0))){w.sep(",");a.unparse(w,0,0);}w.endFunCall(f);
      if(c.operand(1)!=null){w.keyword("WITH");var schema=w.startList("(",")");for(var col:((SqlNodeList)c.operand(1))){w.sep(",");col.unparse(w,0,0);}w.endList(schema);}
    }
  };
  static boolean builtin(SqlCall c){
    if(!(c.getOperator() instanceof SqlFunction f)||!f.getName().equalsIgnoreCase("OPENJSON"))return false;
    var id=f.getSqlIdentifier();return id!=null&&id.isSimple()&&!id.isComponentQuoted(0);
  }
  static int close(List<TsqlSyntaxAdapter.Token> ts,int open){
    if(open>=ts.size()||!ts.get(open).text().equals("("))throw new TsqlParserSupport.Unsupported("OPENJSON_PAREN_REQUIRED");
    int depth=0;for(int i=open;i<ts.size();i++){if(ts.get(i).text().equals("("))depth++;else if(ts.get(i).text().equals(")")&&--depth==0)return i;}
    throw new TsqlParserSupport.Unsupported("OPENJSON_UNCLOSED_PAREN");
  }
  static String identifier(TsqlSyntaxAdapter.Token t){
    if(t.kind().equals("WORD"))return t.text();
    if(!t.kind().equals("IDENT"))throw new TsqlParserSupport.Unsupported("OPENJSON_COLUMN_NAME_REQUIRED");
    String raw=t.text();return raw.charAt(0)=='['?raw.substring(1,raw.length()-1).replace("]]","]"):raw.substring(1,raw.length()-1).replace("\"\"","\"");
  }
  static SqlNode literal(TsqlSyntaxAdapter.Token t,List<Integer> lines){
    if(!t.kind().equals("STRING"))throw new TsqlParserSupport.Unsupported("OPENJSON_LITERAL_COLUMN_PATH_REQUIRED");
    String raw=t.text();boolean national=raw.charAt(0)!='\'';String value=raw.substring(national?2:1,raw.length()-1).replace("''","'");
    var p=TsqlSyntaxAdapter.pos(t.start(),lines);return national?new TsqlParserSupport.NationalLiteral(value,p):SqlLiteral.createCharString(value,p);
  }
  static List<Declaration> prepare(List<TsqlSyntaxAdapter.Token> ts,char[] masked,List<TsqlSyntaxAdapter.National> nationals,List<Integer> lines){
    var declarations=new ArrayList<Declaration>();
    for(int i=0;i+1<ts.size();i++){
      var fn=ts.get(i);if(!TsqlSyntaxAdapter.word(fn,"OPENJSON")||!ts.get(i+1).text().equals("(")||(i>0&&ts.get(i-1).text().equals(".")))continue;
      int argsEnd=close(ts,i+1),with=argsEnd+1;
      boolean apply=i>0&&TsqlSyntaxAdapter.word(ts.get(i-1),"APPLY");
      if(with>=ts.size()||!TsqlSyntaxAdapter.word(ts.get(with),"WITH")){declarations.add(new Declaration(fn.start(),-1,ts.get(argsEnd).end(),null,apply));continue;}
      int end=close(ts,with+1);var columns=new ArrayList<SqlNode>();int k=with+2;
      if(k==end)throw new TsqlParserSupport.Unsupported("OPENJSON_EMPTY_SCHEMA");
      while(k<end){
        var nameToken=ts.get(k++);String name=identifier(nameToken);var namePos=TsqlSyntaxAdapter.pos(nameToken.start(),lines).withQuoting(nameToken.kind().equals("IDENT"));var nameNode=new SqlIdentifier(name,namePos);
        int typeStart=k;if(k>=end||!ts.get(k).kind().equals("WORD"))throw new TsqlParserSupport.Unsupported("OPENJSON_NATIVE_COLUMN_TYPE_REQUIRED");k++;
        if(k<end&&ts.get(k).text().equals("("))k=close(ts,k)+1;
        var types=new ArrayList<TsqlSyntaxAdapter.NativeType>();
        TsqlSyntaxAdapter.captureType(ts,typeStart,k,masked,types);
        if(types.size()!=1)throw new TsqlParserSupport.Unsupported("OPENJSON_COLUMN_TYPE_UNSUPPORTED");
        var type=types.get(0);var typePos=TsqlSyntaxAdapter.pos(type.start(),lines);
        var spec=new SqlDataTypeSpec(new TsqlParserSupport.NativeTypeSpec(type,typePos),typePos);
        SqlNode path=null;if(k<end&&ts.get(k).kind().equals("STRING"))path=literal(ts.get(k++),lines);
        boolean asJson=false;if(k<end&&TsqlSyntaxAdapter.word(ts.get(k),"AS")){
          k++;if(k>=end||!TsqlSyntaxAdapter.word(ts.get(k++),"JSON"))throw new TsqlParserSupport.Unsupported("OPENJSON_AS_JSON_REQUIRED");asJson=true;
          if(!type.name().equals("NVARCHAR")||!type.arguments().equals(List.of("MAX")))throw new TsqlParserSupport.Unsupported("OPENJSON_AS_JSON_REQUIRES_NVARCHAR_MAX");
        }
        columns.add(new SqlBasicCall(COLUMN,Arrays.asList(nameNode,spec,path,SqlLiteral.createBoolean(asJson,namePos)),namePos));
        if(k<end){if(!ts.get(k++).text().equals(",")||k==end)throw new TsqlParserSupport.Unsupported("OPENJSON_COLUMN_SEPARATOR_REQUIRED");}
      }
      int startOffset=ts.get(with).start(),endOffset=ts.get(end).end();
      for(int n=startOffset;n<endOffset;n++)if(masked[n]!='\r'&&masked[n]!='\n')masked[n]=' ';
      nationals.removeIf(n->n.start()>=startOffset&&n.start()<endOffset);
      declarations.add(new Declaration(fn.start(),startOffset,endOffset,new SqlNodeList(columns,TsqlSyntaxAdapter.pos(startOffset,lines)),apply));
    }
    return declarations;
  }
  static SqlNode restore(SqlCall call,List<Declaration> declarations,List<Integer> lines,Set<Integer> seen){
    if(call.getKind()!=SqlKind.COLLECTION_TABLE||call.operandCount()!=1||!(call.operand(0) instanceof SqlCall fn)||!builtin(fn))return call;
    if(fn.operandCount()<1||fn.operandCount()>2||fn.getFunctionQuantifier()!=null)throw new TsqlParserSupport.Unsupported("OPENJSON_ARGUMENT_SHAPE_UNSUPPORTED");
    int offset=TsqlSyntaxAdapter.absolute(fn.getParserPosition(),lines);
    var declaration=declarations.stream().filter(d->d.start()==offset).findFirst().orElseThrow(()->new TsqlParserSupport.Unsupported("OPENJSON_LOCATION_UNVERIFIED"));
    seen.add(offset);
    SqlNode source=new SqlBasicCall(ROW_SOURCE,Arrays.asList(new SqlNodeList(fn.getOperandList(),fn.getParserPosition()),declaration.columns()),fn.getParserPosition());
    return declaration.apply()?org.apache.calcite.sql.fun.SqlStdOperatorTable.LATERAL.createCall(fn.getParserPosition(),source):source;
  }
}
