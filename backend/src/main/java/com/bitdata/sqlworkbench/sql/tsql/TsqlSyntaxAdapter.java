package com.bitdata.sqlworkbench.sql.tsql;

import org.apache.calcite.sql.*;
import org.apache.calcite.sql.parser.*;
import org.apache.calcite.sql.parser.babel.SqlBabelParserImpl;
import org.apache.calcite.sql.dialect.MssqlSqlDialect;
import org.apache.calcite.sql.fun.SqlBetweenOperator;
import org.apache.calcite.sql.util.SqlShuttle;
import org.apache.calcite.util.NlsString;
import com.bitdata.sqlworkbench.sql.*;
import com.bitdata.sqlworkbench.connection.*;
import java.io.*;
import java.util.*;

import static com.bitdata.sqlworkbench.sql.tsql.TsqlParserSupport.*;

/** Source-position preserving T-SQL lexical adapter. */
final class TsqlSyntaxAdapter {
  record Token(String kind,String text,int start,int end) {}
  record Top(int selectStart,String count,int start,int end) {}
  record National(String value,int start,int end) {}
  record NativeType(String name,List<String> arguments,int start,int end) {}
  record Conversion(String name,int start) {}
  record Prepared(String text,List<Top> tops,List<National> nationals,List<NativeType> types,List<Conversion> conversions,List<Integer> lines,List<TsqlOpenJsonAdapter.Declaration> json) {}
  static boolean word(Token t,String s){return t.kind().equals("WORD")&&t.text().equalsIgnoreCase(s);}
  static int absolute(SqlParserPos p,List<Integer> lines){return p.getLineNum()<1||p.getColumnNum()<1||p.getLineNum()>lines.size()?-1:lines.get(p.getLineNum()-1)+p.getColumnNum()-1;}
  static SqlParserPos pos(int offset,List<Integer> lines){int line=Collections.binarySearch(lines,offset);if(line<0)line=-line-2;return new SqlParserPos(line+1,offset-lines.get(line)+1);}
  static Prepared prepare(String sql) {
    var tokens=new ArrayList<Token>();var national=new ArrayList<National>();var starts=new ArrayList<Integer>();starts.add(0);
    for(int n=0;n<sql.length();n++)if(sql.charAt(n)=='\n')starts.add(n+1);
    char[] masked=sql.toCharArray();
    for(int i=0;i<sql.length();) {
      int begin=i;char c=sql.charAt(i);
      if(Character.isWhitespace(c)){i++;continue;}
      if(c=='-'&&i+1<sql.length()&&sql.charAt(i+1)=='-'){i+=2;while(i<sql.length()&&sql.charAt(i)!='\n')i++;continue;}
      if(c=='/'&&i+1<sql.length()&&sql.charAt(i+1)=='*'){i+=2;int depth=1;while(i<sql.length()&&depth>0){if(i+1<sql.length()&&sql.startsWith("/*",i)){depth++;i+=2;}else if(i+1<sql.length()&&sql.startsWith("*/",i)){depth--;i+=2;}else i++;}if(depth>0)throw new TsqlParserSupport.Unsupported("UNTERMINATED_COMMENT");for(int j=begin;j<i;j++)if(masked[j]!='\r'&&masked[j]!='\n')masked[j]=' ';continue;}
      boolean nPrefix=(c=='N'||c=='n')&&i+1<sql.length()&&sql.charAt(i+1)=='\'';
      if(c=='\''||nPrefix){if(nPrefix)i++;i++;var value=new StringBuilder();boolean closed=false;
        while(i<sql.length()){char q=sql.charAt(i++);if(q=='\''){if(i<sql.length()&&sql.charAt(i)=='\''){value.append('\'');i++;}else{closed=true;break;}}else value.append(q);}
        if(!closed)throw new TsqlParserSupport.Unsupported("UNTERMINATED_STRING");
        if(nPrefix){national.add(new National(value.toString(),begin,i));for(int j=begin+2;j<i-1;j++)if(masked[j]!='\r'&&masked[j]!='\n'&&masked[j]!='\'')masked[j]='x';}
        tokens.add(new Token("STRING",sql.substring(begin,i),begin,i));continue;
      }
      if(c=='['||c=='"'){char end=c=='['?']':'"';i++;boolean closed=false;while(i<sql.length()){if(sql.charAt(i++)==end){if(i<sql.length()&&sql.charAt(i)==end)i++;else{closed=true;break;}}}if(!closed)throw new TsqlParserSupport.Unsupported("UNTERMINATED_IDENTIFIER");tokens.add(new Token("IDENT",sql.substring(begin,i),begin,i));continue;}
      if(Character.isLetter(c)||c=='_'||c=='@'||c=='#'){i++;while(i<sql.length()&&(Character.isLetterOrDigit(sql.charAt(i))||"_@$#".indexOf(sql.charAt(i))>=0))i++;tokens.add(new Token("WORD",sql.substring(begin,i),begin,i));continue;}
      if(Character.isDigit(c)){i++;while(i<sql.length()&&Character.isDigit(sql.charAt(i)))i++;tokens.add(new Token("NUMBER",sql.substring(begin,i),begin,i));continue;}
      tokens.add(new Token("SYMBOL",Character.toString(c),i,i+1));i++;
    }
    // Babel also knows PostgreSQL casts. Do not widen T-SQL acceptance when
    // adopting its grammar; strings, quoted identifiers and comments are tokens
    // of their own and cannot trigger this operator check.
    for (int i = 1; i < tokens.size(); i++) {
      if (tokens.get(i - 1).text().equals(":") && tokens.get(i).text().equals(":"))
        throw new TsqlParserSupport.Unsupported("POSTGRES_CAST_UNSUPPORTED_IN_TSQL");
    }
    var json=TsqlOpenJsonAdapter.prepare(tokens,masked,national,starts);
    var tops=new ArrayList<Top>();
    for(int i=0;i<tokens.size();i++)if(word(tokens.get(i),"SELECT")) {
      int j=i+1;if(j<tokens.size()&&(word(tokens.get(j),"DISTINCT")||word(tokens.get(j),"ALL")))j++;
      if(j>=tokens.size()||!word(tokens.get(j),"TOP"))continue;
      int top=j++;boolean paren=j<tokens.size()&&tokens.get(j).text().equals("(");if(paren)j++;
      if(j>=tokens.size()||!tokens.get(j).kind().equals("NUMBER"))throw new TsqlParserSupport.Unsupported("TOP_CONSTANT_INTEGER_REQUIRED");
      String count=tokens.get(j++).text();int end=tokens.get(j-1).end();
      if(paren){if(j>=tokens.size()||!tokens.get(j).text().equals(")"))throw new TsqlParserSupport.Unsupported("TOP_EXPRESSION_UNSUPPORTED");end=tokens.get(j++).end();}
      if(j<tokens.size()&&(word(tokens.get(j),"PERCENT")||word(tokens.get(j),"WITH")))throw new TsqlParserSupport.Unsupported("TOP_PERCENT_OR_TIES_UNSUPPORTED");
      if(new java.math.BigInteger(count).compareTo(java.math.BigInteger.valueOf(Long.MAX_VALUE))>0)throw new TsqlParserSupport.Unsupported("TOP_COUNT_OUT_OF_RANGE");
      tops.add(new Top(tokens.get(i).start(),count,tokens.get(top).start(),end));
      for(int k=tokens.get(top).start();k<end;k++)if(masked[k]!='\r'&&masked[k]!='\n')masked[k]=' ';
    }
    var types=new ArrayList<NativeType>();var conversions=new ArrayList<Conversion>();
    for(int i=0;i+1<tokens.size();i++){
      var function=tokens.get(i);if(!function.kind().equals("WORD")||!tokens.get(i+1).text().equals("(")||(i>0&&tokens.get(i-1).text().equals(".")))continue;
      if(word(function,"CAST")){int depth=0;for(int j=i+2;j<tokens.size();j++){var t=tokens.get(j);if(t.text().equals("("))depth++;else if(t.text().equals(")")){if(depth--==0)break;}else if(depth==0&&word(t,"AS")){int end=j+1,inner=0;for(;end<tokens.size();end++){var x=tokens.get(end);if(x.text().equals("("))inner++;else if(x.text().equals(")")){if(inner--==0)break;}}captureType(tokens,j+1,end,masked,types);break;}}}
      else if(word(function,"CONVERT")||word(function,"TRY_CONVERT")){int end=i+2,depth=0;for(;end<tokens.size();end++){var t=tokens.get(end);if(t.text().equals("("))depth++;else if(t.text().equals(")")){if(depth--==0)break;}else if(depth==0&&t.text().equals(","))break;}int before=types.size();captureType(tokens,i+2,end,masked,types);if(types.size()!=before+1)throw new TsqlParserSupport.Unsupported("CONVERSION_TYPE_UNSUPPORTED");var type=types.get(types.size()-1);for(int k=type.start();k<type.start()+3;k++)masked[k]=' ';masked[type.start()]='T';for(int k=function.start();k<function.end();k++)masked[k]=' ';char[] marker="M3CNVRT".toCharArray();System.arraycopy(marker,0,masked,function.start(),marker.length);conversions.add(new Conversion(function.text().toUpperCase(Locale.ROOT),function.start()));}
    }
    return new Prepared(new String(masked),tops,national,types,conversions,starts,json);
  }
  static final Set<String> NATIVE_TYPES=Set.of("CHAR","VARCHAR","NCHAR","NVARCHAR","BINARY","VARBINARY","BIT","TINYINT","SMALLINT","INT","INTEGER","BIGINT","DECIMAL","NUMERIC","MONEY","SMALLMONEY","REAL","FLOAT","DATE","TIME","DATETIME","SMALLDATETIME","DATETIME2","DATETIMEOFFSET","UNIQUEIDENTIFIER","XML","SQL_VARIANT");
  static void captureType(List<Token> tokens,int first,int last,char[] masked,List<NativeType> types){
    if(first>=last||!tokens.get(first).kind().equals("WORD"))return;
    String name=tokens.get(first).text().toUpperCase(Locale.ROOT);if(!NATIVE_TYPES.contains(name))return;
    var arguments=new ArrayList<String>();int index=first+1;
    if(index<last){if(!tokens.get(index++).text().equals("("))throw new TsqlParserSupport.Unsupported("TYPE_DECLARATION_UNSUPPORTED");boolean needValue=true;for(;index<last-1;index++){var token=tokens.get(index);if(needValue){if(!token.kind().equals("NUMBER")&&!word(token,"MAX"))throw new TsqlParserSupport.Unsupported("TYPE_ARGUMENT_UNSUPPORTED");arguments.add(token.text().toUpperCase(Locale.ROOT));}else if(!token.text().equals(","))throw new TsqlParserSupport.Unsupported("TYPE_ARGUMENT_UNSUPPORTED");needValue=!needValue;}if(index!=last-1||!tokens.get(index).text().equals(")")||needValue)throw new TsqlParserSupport.Unsupported("TYPE_DECLARATION_UNSUPPORTED");}
    validateType(name,arguments);int start=tokens.get(first).start(),end=tokens.get(last-1).end();types.add(new NativeType(name,List.copyOf(arguments),start,end));
    for(int k=start;k<end;k++)if(masked[k]!='\r'&&masked[k]!='\n')masked[k]=' ';masked[start]='I';masked[start+1]='N';masked[start+2]='T';
  }
  static void validateType(String name,List<String> args){
    int maxArgs=Set.of("DECIMAL","NUMERIC").contains(name)?2:Set.of("CHAR","VARCHAR","NCHAR","NVARCHAR","BINARY","VARBINARY","FLOAT","TIME","DATETIME2","DATETIMEOFFSET").contains(name)?1:0;
    if(args.size()>maxArgs)throw new TsqlParserSupport.Unsupported("TYPE_ARGUMENT_ARITY_UNSUPPORTED");if(args.isEmpty())return;
    String first=args.get(0);if(first.equals("MAX")){if(args.size()!=1||!Set.of("VARCHAR","NVARCHAR","VARBINARY").contains(name))throw new TsqlParserSupport.Unsupported("TYPE_MAX_UNSUPPORTED");return;}
    int value;try{value=Integer.parseInt(first);}catch(NumberFormatException e){throw new TsqlParserSupport.Unsupported("TYPE_ARGUMENT_OUT_OF_RANGE");}
    int lower=Set.of("TIME","DATETIME2","DATETIMEOFFSET").contains(name)?0:1;
    int upper=switch(name){case "NCHAR","NVARCHAR"->4000;case "CHAR","VARCHAR","BINARY","VARBINARY"->8000;case "FLOAT"->53;case "TIME","DATETIME2","DATETIMEOFFSET"->7;case "DECIMAL","NUMERIC"->38;default->0;};
    if(value<lower||value>upper)throw new TsqlParserSupport.Unsupported("TYPE_ARGUMENT_OUT_OF_RANGE");
    if(args.size()==2){try{int scale=Integer.parseInt(args.get(1));if(scale<0||scale>value)throw new TsqlParserSupport.Unsupported("TYPE_SCALE_OUT_OF_RANGE");}catch(NumberFormatException e){throw new TsqlParserSupport.Unsupported("TYPE_SCALE_OUT_OF_RANGE");}}
  }
}
