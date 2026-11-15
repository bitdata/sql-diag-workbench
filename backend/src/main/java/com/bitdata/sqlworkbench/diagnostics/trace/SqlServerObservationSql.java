package com.bitdata.sqlworkbench.diagnostics.trace;

import com.bitdata.sqlworkbench.connection.DatabaseStrategyFactory;
import com.bitdata.sqlworkbench.sql.*;
import com.bitdata.sqlworkbench.diagnostics.trace.KeyValue;
import org.apache.calcite.sql.*;
import java.sql.*;
import java.util.*;

/** Native SQLServer observation SQL. Keeps CTEs outside derived query wrappers. */
public final class SqlServerObservationSql {
  static final SqlDialectResolver RESOLVER=new SqlDialectResolver(DatabaseStrategyFactory.withBuiltinStrategies());
  static final DialectProfile PROFILE=RESOLVER.getProfile("sqlserver");

  public record Query(String sql,List<KeyValue> parameters) {}
  record Body(String prefix,String body) {}
  public static String quote(String text){return "["+text.replace("]","]]")+"]";}
  static String write(SqlNode node){return NodeToSql.toQuotedSql(node,PROFILE);}
  static Body body(String original)throws Exception {
    new SelectOnlyGuard(RESOLVER).requireSingleSelect(original,"sqlserver");
    SqlNode node=org.apache.calcite.sql.parser.SqlParser.create(original,RESOLVER.parserConfig("sqlserver")).parseQuery();
    String prefix="";
    if(node instanceof SqlOrderBy order&&order.query instanceof SqlWith with)
      node=new SqlWith(with.getParserPosition(),with.withList,new SqlOrderBy(order.getParserPosition(),with.body,order.orderList,order.offset,order.fetch));
    if(node instanceof SqlWith with){
      List<String> items=new ArrayList<>();
      for(SqlNode n:with.withList){
        SqlWithItem item=(SqlWithItem)n;
        if(item.recursive!=null&&item.recursive.booleanValue())throw new SqlSerializationException("RECURSIVE_CTE_UNVERIFIED");
        String columns=item.columnList==null?"":" ("+item.columnList.getList().stream().map(SqlServerObservationSql::write).collect(java.util.stream.Collectors.joining(","))+")";
        items.add(write(item.name)+columns+" AS ("+write(item.query)+")");
      }
      prefix="WITH "+String.join(",",items)+" ";node=with.body;
    }
    if(node instanceof SqlOrderBy order&&order.orderList.size()>0&&order.fetch==null
      &&(!(order.query instanceof SqlSelect select)||select.getFetch()==null))throw new SqlSerializationException("ORDERED_DERIVED_QUERY_WITHOUT_TOP_UNSUPPORTED");
    return new Body(prefix,write(node));
  }
  public static Query build(String original,List<KeyValue> keys,String mode)throws Exception {
    return build(original,keys,keys.stream().map(k->k.column().substring(k.column().lastIndexOf('.')+1)).toList(),mode);
  }
  public static Query build(String original,List<KeyValue> keys,List<String> labels,String mode)throws Exception {
    Body body=body(original);
    if(mode.equals("METADATA"))return new Query(body.prefix()+"SELECT TOP (0) * FROM ("+body.body()+") AS [__observe]",List.of());
    if(keys.isEmpty()||keys.size()>16)throw new SqlSerializationException("KEY_INPUT_INVALID");
    List<String> predicates=new ArrayList<>();List<KeyValue> parameters=new ArrayList<>();
    Set<String> seen=new HashSet<>();
    for(int index=0;index<keys.size();index++){
      KeyValue key=keys.get(index);String label=labels.get(index);
      if(!seen.add(label))throw new SqlSerializationException("KEY_OUTPUT_DUPLICATE");
      predicates.add("[__observe]."+quote(label)+(key.value()==null?" IS NULL":" = ?"));
      if(key.value()!=null)parameters.add(key);
    }
    String inner=" FROM ("+body.body()+") AS [__observe] WHERE "+String.join(" AND ",predicates);
    return new Query(body.prefix()+(mode.equals("COUNT")?"SELECT COUNT_BIG(*)"+inner:
      "SELECT CASE WHEN EXISTS (SELECT 1"+inner+") THEN 1 ELSE 0 END"),List.copyOf(parameters));
  }
  public static void bind(PreparedStatement statement,List<KeyValue> keys)throws SQLException {
    int i=0;
    for(KeyValue key:keys){
      if(key.type()==KeyValue.Type.DECIMAL)statement.setBigDecimal(++i,new java.math.BigDecimal((String)key.value()));
      else if(key.type()==KeyValue.Type.TEXT)statement.setNString(++i,(String)key.value());
      else if(key.type()==KeyValue.Type.BOOLEAN)statement.setBoolean(++i,(Boolean)key.value());
      else throw new SqlSerializationException("TYPED_KEY_REQUIRED");
    }
  }
}
