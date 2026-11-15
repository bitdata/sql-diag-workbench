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

/** Executable T-SQL writer; version gates are explicit, independent of parser acceptance. */
public final class TsqlExecutableDialect extends MssqlSqlDialect {
    final int major;
    public TsqlExecutableDialect(int major){super(DEFAULT_CONTEXT.withDatabaseMajorVersion(major));this.major=major;}
    @Override public void unparseOffsetFetch(SqlWriter w,SqlNode offset,SqlNode fetch){if(offset!=null&&major<11)throw new SqlSerializationException("OFFSET_FETCH_UNSUPPORTED_ON_LEGACY");super.unparseOffsetFetch(w,offset,fetch);}
    @Override public void unparseCall(SqlWriter w,SqlCall call,int left,int right){
      if(call.getOperator()==TsqlOpenJsonAdapter.ROW_SOURCE){if(major<13)throw new SqlSerializationException("OPENJSON_REQUIRES_SQLSERVER_2016_OR_LATER");call.getOperator().unparse(w,call,left,right);return;}
      if(call.getOperator() instanceof SqlBetweenOperator b){
        if(b.flag==SqlBetweenOperator.Flag.SYMMETRIC)throw new SqlSerializationException("BETWEEN_SYMMETRIC_UNSUPPORTED");
        call.operand(0).unparse(w,b.getLeftPrec(),0);w.keyword(b.isNegated()?"NOT BETWEEN":"BETWEEN");var f=w.startList("(",")");call.operand(1).unparse(w,0,0);w.endList(f);w.keyword("AND");f=w.startList("(",")");call.operand(2).unparse(w,0,0);w.endList(f);return;
      }
      if(call.getOperator() instanceof SqlFunction fn&&Set.of("ISNULL","SQL_VARIANT_PROPERTY","DATALENGTH","GREATEST","LEAST").contains(fn.getName().toUpperCase(Locale.ROOT))&&fn.getSqlIdentifier()!=null&&fn.getSqlIdentifier().isSimple()&&!fn.getSqlIdentifier().isComponentQuoted(0)){
        boolean extrema=Set.of("GREATEST","LEAST").contains(fn.getName().toUpperCase(Locale.ROOT));
        if(extrema&&major<16)throw new SqlSerializationException("EXTREMA_REQUIRE_SQLSERVER_2022_OR_LATER");
        int expected=fn.getName().equalsIgnoreCase("DATALENGTH")?1:2;
        if(extrema?(call.operandCount()<1||call.operandCount()>254):call.operandCount()!=expected)throw new SqlSerializationException("BUILTIN_ARITY_UNSUPPORTED");
        if(call.getFunctionQuantifier()!=null)throw new SqlSerializationException("BUILTIN_QUANTIFIER_UNSUPPORTED");
        var frame=w.startFunCall(fn.getName().toUpperCase(Locale.ROOT));for(var arg:call.getOperandList()){w.sep(",");arg.unparse(w,0,0);}w.endFunCall(frame);return;
      }
      if(call instanceof SqlJoin join){
        SqlNode rhs=join.getRight(),alias=null;if(rhs instanceof SqlCall a&&a.getKind()==SqlKind.AS){rhs=a.operand(0);alias=a.operand(1);}
        if(rhs instanceof SqlCall lateral&&lateral.getKind()==SqlKind.LATERAL){
          boolean outerTrue=join.getJoinType()==JoinType.LEFT&&join.getConditionType()==JoinConditionType.ON&&join.getCondition() instanceof SqlLiteral condition&&condition.getTypeName()==org.apache.calcite.sql.type.SqlTypeName.BOOLEAN&&condition.booleanValue();
          if(join.isNatural()||(join.getCondition()!=null&&!outerTrue)||!(join.getJoinType()==JoinType.CROSS||join.getJoinType()==JoinType.LEFT))throw new SqlSerializationException("APPLY_JOIN_SHAPE_UNSUPPORTED");
          join.getLeft().unparse(w,0,0);w.keyword(join.getJoinType()==JoinType.CROSS?"CROSS APPLY":"OUTER APPLY");
          if(lateral.operand(0) instanceof SqlCall source&&source.getOperator()==TsqlOpenJsonAdapter.ROW_SOURCE)source.unparse(w,0,0);
          else{var frame=w.startList("(",")");lateral.operand(0).unparse(w,0,0);w.endList(frame);}
          if(alias!=null){w.keyword("AS");alias.unparse(w,0,0);}return;
        }
      }
      if(call.getKind()==SqlKind.OVER && call.operand(0) instanceof SqlCall aggregate
          && aggregate.getOperator() instanceof SqlFunction agg
          && agg.getSqlIdentifier()!=null && agg.getSqlIdentifier().isSimple() && !agg.getSqlIdentifier().isComponentQuoted(0)
          && agg.getName().equalsIgnoreCase("COUNT_BIG")
          && aggregate.getFunctionQuantifier()!=null
          && aggregate.getFunctionQuantifier().toString().equalsIgnoreCase("DISTINCT"))
        throw new SqlSerializationException("COUNT_BIG_DISTINCT_WINDOW_UNSUPPORTED");
      if(call.getOperator() instanceof SqlFunction fn && fn.getSqlIdentifier()!=null
          && fn.getSqlIdentifier().isSimple() && !fn.getSqlIdentifier().isComponentQuoted(0)) {
        String name=fn.getName().toUpperCase(Locale.ROOT);
        int lo=switch(name){case "COUNT_BIG","LEN","CHECKSUM" -> 1;case "CHARINDEX","CONCAT","HASHBYTES" -> 2;case "GETDATE","NEWID" -> 0;default -> -1;};
        if(lo>=0){
          int hi=switch(name){case "CHARINDEX" -> 3;case "CHECKSUM" -> Integer.MAX_VALUE;case "CONCAT" -> 254;default -> lo;};
          if(call.operandCount()<lo||call.operandCount()>hi)throw new SqlSerializationException("BUILTIN_ARITY_UNSUPPORTED");
          boolean star=call.getOperandList().stream().anyMatch(n->n instanceof SqlIdentifier id&&id.isStar());
          if(star&&call.getOperandList().stream().anyMatch(n->n instanceof SqlIdentifier id&&id.isStar()&&id.names.size()!=1))throw new SqlSerializationException("QUALIFIED_STAR_UNSUPPORTED");
          if(star&&!name.equals("COUNT_BIG"))throw new SqlSerializationException("SCALAR_STAR_UNSUPPORTED");
          if(call.getFunctionQuantifier()!=null){
            String q=call.getFunctionQuantifier().toString();
            if(!name.equals("COUNT_BIG")||star||!Set.of("ALL","DISTINCT").contains(q.toUpperCase(Locale.ROOT)))throw new SqlSerializationException("BUILTIN_QUANTIFIER_UNSUPPORTED");
          }
          var frame=w.startFunCall(name);
          if(call.getFunctionQuantifier()!=null)call.getFunctionQuantifier().unparse(w,0,0);
          for(var arg:call.getOperandList()){w.sep(",");arg.unparse(w,0,0);}
          w.endFunCall(frame);return;
        }
      }
      super.unparseCall(w,call,left,right);
    }
  }
