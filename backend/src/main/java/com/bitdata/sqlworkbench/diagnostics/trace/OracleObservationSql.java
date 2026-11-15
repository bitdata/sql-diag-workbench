package com.bitdata.sqlworkbench.diagnostics.trace;

import com.bitdata.sqlworkbench.connection.DatabaseStrategyFactory;
import com.bitdata.sqlworkbench.sql.*;
import org.apache.calcite.sql.*;
import org.apache.calcite.sql.parser.SqlParser;
import java.sql.*;
import java.util.*;

/** Oracle AST observation SQL. Metadata labels are server facts, never key SQL. */
public final class OracleObservationSql {
 static final SqlDialectResolver resolver=new SqlDialectResolver(DatabaseStrategyFactory.withBuiltinStrategies());
 static final DialectProfile profile=resolver.getProfile("oracle");
 public record Column(String label,int jdbcType) {}
 public record Query(String sql,List<KeyValue> parameters) {}
 record Body(String prefix,String body) {}
 static String write(SqlNode n){return NodeToSql.toQuotedSql(n,profile);}
 static String quote(String s){return "\""+s.replace("\"","\"\"")+"\"";}
 static void reject(String reason){throw new SqlSerializationException(reason);}
 static Body body(String sql)throws Exception{
  new SelectOnlyGuard(resolver).requireSingleSelect(sql,"oracle");
  SqlNode node=SqlParser.create(sql,resolver.parserConfig("oracle")).parseQuery();
  node.accept(new org.apache.calcite.sql.util.SqlShuttle(){
   @Override public SqlNode visit(SqlIdentifier id){
    if(id.names.stream().anyMatch(n->n.contains("\"")))reject("ORACLE_QUOTED_IDENTIFIER_INVALID");
    return id;
   }
   @Override public SqlNode visit(SqlDynamicParam param){reject("ORIGINAL_PARAMETERS_UNSUPPORTED");return param;}
  });
  if(node instanceof SqlOrderBy o&&o.query instanceof SqlWith w)
   node=new SqlWith(w.getParserPosition(),w.withList,new SqlOrderBy(o.getParserPosition(),w.body,o.orderList,o.offset,o.fetch));
  String prefix="";
  if(node instanceof SqlWith w){
   List<String> items=new ArrayList<>();
   for(SqlNode n:w.withList){
    SqlWithItem item=(SqlWithItem)n;
    if(item.recursive!=null&&item.recursive.booleanValue())reject("RECURSIVE_CTE_UNVERIFIED");
    String columns=item.columnList==null?"":" ("+String.join(",",item.columnList.getList().stream().map(OracleObservationSql::write).toList())+")";
    items.add(write(item.name)+columns+" AS ("+write(item.query)+")");
   }
   prefix="WITH "+String.join(",",items)+" ";node=w.body;
  }
  return new Body(prefix,write(node));
 }
 public static Query metadata(String sql)throws Exception{
  Body b=body(sql);return new Query(b.prefix+"SELECT * FROM ("+b.body+") \"__observe\" WHERE 1=0",List.of());
 }
 public static Query build(String sql,List<Column> columns,List<KeyValue> keys,List<String> labels,String mode)throws Exception{
  if(!Set.of("COUNT","EXISTS").contains(mode))reject("OBSERVATION_MODE_INVALID");
  if(keys.isEmpty()||keys.size()>16||keys.size()!=labels.size())reject("KEY_INPUT_INVALID");
  Map<String,Column> byLabel=new HashMap<>();
  for(Column c:columns){
   if(c.label()==null||c.label().isBlank())reject("OUTPUT_LABEL_MISSING");
   if(c.label().contains("\""))reject("ORACLE_OUTPUT_LABEL_INVALID");
   if(byLabel.put(c.label(),c)!=null)reject("OUTPUT_LABEL_DUPLICATE");
  }
  List<String> predicates=new ArrayList<>();List<KeyValue> params=new ArrayList<>();Set<String> seen=new HashSet<>();
  for(int i=0;i<keys.size();i++){
   String label=labels.get(i);Column c=byLabel.get(label);KeyValue k=keys.get(i);
   if(c==null)reject("KEY_OUTPUT_NOT_FOUND");
   if(!seen.add(label))reject("KEY_OUTPUT_DUPLICATE");
   int t=c.jdbcType();
   boolean number=Set.of(Types.NUMERIC,Types.DECIMAL,Types.INTEGER,Types.SMALLINT,Types.BIGINT).contains(t);
   boolean text=t==Types.VARCHAR||t==Types.NVARCHAR;
   boolean bool=t==Types.BOOLEAN;
   if(t==Types.CHAR||t==Types.NCHAR)reject("ORACLE_FIXED_CHAR_KEY_UNVERIFIED");
   if(!number&&!text&&!bool)reject("ORACLE_KEY_TYPE_UNSUPPORTED");
   if(k.type()==null)reject("TYPED_KEY_REQUIRED");
   if(k.type()!=KeyValue.Type.NULL){
    if(k.type()==KeyValue.Type.TEXT&&((String)k.value()).isEmpty())reject("ORACLE_EMPTY_TEXT_KEY_UNSUPPORTED");
    if(!(number&&k.type()==KeyValue.Type.DECIMAL||text&&k.type()==KeyValue.Type.TEXT||bool&&k.type()==KeyValue.Type.BOOLEAN))reject("KEY_TYPE_MISMATCH");
   }
   predicates.add("\"__observe\"."+quote(label)+(k.type()==KeyValue.Type.NULL?" IS NULL":" = ?"));
   if(k.type()!=KeyValue.Type.NULL)params.add(k);
  }
  Body b=body(sql);String inner=" FROM ("+b.body+") \"__observe\" WHERE "+String.join(" AND ",predicates);
  return new Query(b.prefix+(mode.equals("COUNT")?"SELECT COUNT(*)"+inner:"SELECT CASE WHEN EXISTS (SELECT 1"+inner+") THEN 1 ELSE 0 END FROM dual"),List.copyOf(params));
 }
 public static void bind(PreparedStatement s,List<KeyValue> keys,List<Column> columns,List<String> labels)throws SQLException{
  int index=0;
  for(int i=0;i<keys.size();i++){
   KeyValue k=keys.get(i);if(k.type()==KeyValue.Type.NULL)continue;
   if(k.type()==KeyValue.Type.DECIMAL)s.setBigDecimal(++index,new java.math.BigDecimal((String)k.value()));
   else if(k.type()==KeyValue.Type.BOOLEAN)s.setBoolean(++index,(Boolean)k.value());
   else if(k.type()==KeyValue.Type.TEXT){
    String label=labels.get(i);int type=columns.stream().filter(c->c.label().equals(label)).findFirst().orElseThrow().jdbcType();
    if(type==Types.NVARCHAR)s.setNString(++index,(String)k.value());else s.setString(++index,(String)k.value());
   }
   else reject("BIND_TYPE_INVALID");
  }
 }
}
