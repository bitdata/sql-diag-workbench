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

import static com.bitdata.sqlworkbench.sql.tsql.TsqlSyntaxAdapter.*;

/** T-SQL parser factory and semantic AST restoration. */
public final class TsqlParserSupport {
  private TsqlParserSupport() {}
  public static final SqlParserImplFactory FACTORY = Parser::new;
  public static final class Unsupported extends IllegalArgumentException {
    public Unsupported(String code) { super(code); }
  }
  public static final class NativeTypeSpec extends SqlUserDefinedTypeNameSpec {
    final NativeType type;
    public String nativeName() { return type.name(); }
    public List<String> arguments() { return type.arguments(); }
    NativeTypeSpec(NativeType type,SqlParserPos p){super(type.name(),p);this.type=type;}
    @Override public void unparse(SqlWriter w,int left,int right){w.keyword(type.name());if(!type.arguments().isEmpty()){var frame=w.startList("(",")");for(var arg:type.arguments()){w.sep(",");w.literal(arg);}w.endList(frame);}}
    @Override public boolean equalsDeep(SqlTypeNameSpec other,org.apache.calcite.util.Litmus litmus){return other instanceof NativeTypeSpec n&&type.name().equals(n.type.name())&&type.arguments().equals(n.type.arguments())?litmus.succeed():litmus.fail("Native type declarations differ");}
    @Override public org.apache.calcite.rel.type.RelDataType deriveType(org.apache.calcite.sql.validate.SqlValidator validator){throw new Unsupported("NATIVE_TYPE_CALCITE_VALIDATION_UNIMPLEMENTED");}
  }
  public static final class NationalLiteral extends SqlCharStringLiteral {
    final String text;
    public String text() { return text; }
    NationalLiteral(String text,SqlParserPos p){super(new NlsString(text,"UTF-16LE",null),p);this.text=text;}
    @Override public void unparse(SqlWriter w,int left,int right){w.literal("N'"+text.replace("'","''")+"'");}
    @Override public SqlCharStringLiteral clone(SqlParserPos p){return new NationalLiteral(text,p);}
  }
  static SqlNode restore(SqlNode root,Prepared prepared) {
    var seen=new HashSet<Integer>();
    SqlNode result=root.accept(new SqlShuttle(){
      @Override public SqlNode visit(SqlIdentifier id){int offset=absolute(id.getParserPosition(),prepared.lines());for(var type:prepared.types())if(type.start()==offset){seen.add(offset);return new SqlDataTypeSpec(new NativeTypeSpec(type,id.getParserPosition()),id.getParserPosition());}return id;}
      @Override public SqlNode visit(SqlDataTypeSpec spec){int offset=absolute(spec.getParserPosition(),prepared.lines());for(var type:prepared.types())if(type.start()==offset){seen.add(offset);return new SqlDataTypeSpec(new NativeTypeSpec(type,spec.getParserPosition()),spec.getTimeZone(),spec.getNullable(),spec.getParserPosition());}return spec;}
      @Override public SqlNode visit(SqlLiteral literal){
        int offset=absolute(literal.getParserPosition(),prepared.lines());
        for(var n:prepared.nationals())if(n.start()==offset){seen.add(offset);return new NationalLiteral(n.value(),literal.getParserPosition());}
        return literal;
      }
      @Override public SqlNode visit(SqlCall call){
        SqlNode node=super.visit(call);
        if(node instanceof SqlCall restored)node=TsqlOpenJsonAdapter.restore(restored,prepared.json(),prepared.lines(),seen);
        int callOffset=absolute(call.getParserPosition(),prepared.lines());for(var conversion:prepared.conversions())if(conversion.start()==callOffset&&call.getOperator().getName().equals("M3CNVRT")){if(!(node instanceof SqlCall fn)||fn.operandCount()<2||fn.operandCount()>3||!(fn.operand(0) instanceof SqlDataTypeSpec))throw new Unsupported("CONVERSION_AST_SHAPE_UNSUPPORTED");seen.add(conversion.start());var operator=conversion.name().equals("CONVERT")?org.apache.calcite.sql.fun.SqlLibraryOperators.MSSQL_CONVERT:new SqlFunction("TRY_CONVERT",SqlKind.OTHER_FUNCTION,null,null,null,SqlFunctionCategory.SYSTEM);node=operator.createCall(fn.getParserPosition(),fn.getOperandList().toArray(SqlNode[]::new));}
        if(node instanceof SqlSelect select){int offset=absolute(select.getParserPosition(),prepared.lines());for(var top:prepared.tops())if(top.selectStart()==offset){
          if(select.getFetch()!=null||select.getOffset()!=null)throw new Unsupported("TOP_WITH_PAGINATION_UNSUPPORTED");
          select.setFetch(SqlLiteral.createExactNumeric(top.count(),pos(top.start(),prepared.lines())));seen.add(top.start());
        }}
        if(node instanceof SqlOrderBy order && order.query instanceof SqlSelect select && select.getFetch()!=null && (order.fetch!=null||order.offset!=null))throw new Unsupported("TOP_WITH_PAGINATION_UNSUPPORTED");
        return node;
      }
    });
    for(var top:prepared.tops())if(!seen.contains(top.start()))throw new Unsupported("TOP_AST_LOCATION_UNVERIFIED");
    for(var literal:prepared.nationals())if(!seen.contains(literal.start()))throw new Unsupported("NATIONAL_LITERAL_LOCATION_UNVERIFIED");
    for(var type:prepared.types())if(!seen.contains(type.start()))throw new Unsupported("TYPE_AST_LOCATION_UNVERIFIED");
    for(var conversion:prepared.conversions())if(!seen.contains(conversion.start()))throw new Unsupported("CONVERSION_AST_LOCATION_UNVERIFIED");
    for(var json:prepared.json())if(!seen.contains(json.start()))throw new Unsupported("OPENJSON_ONLY_TABLE_SOURCE_SUPPORTED");
    return result;
  }
  static final class Parser extends SqlBabelParserImpl {
    Prepared prepared;
    Parser(Reader reader){super(new StringReader(""));ReInit(reader);}
    @Override public void ReInit(Reader reader){
      try{var buffer=new StringBuilder();char[] chars=new char[4096];for(int n;(n=reader.read(chars))!=-1;)buffer.append(chars,0,n);prepared=prepare(buffer.toString());
        var stream=new org.apache.calcite.sql.parser.babel.SimpleCharStream(new StringReader(prepared.text())){{setTabSize(1);}};
        super.ReInit(new org.apache.calcite.sql.parser.babel.SqlBabelParserImplTokenManager(stream));
      }catch(IOException e){throw new UncheckedIOException(e);}
    }
    @Override public SqlNode parseSqlStmtEof()throws Exception{return restore(super.parseSqlStmtEof(),prepared);}
    @Override public SqlNode parseSqlExpressionEof()throws Exception{return restore(super.parseSqlExpressionEof(),prepared);}
  }
}
