package com.bitdata.sqlworkbench.execution;
import com.fasterxml.jackson.databind.*;
import java.sql.*;
import java.util.*;
import java.util.function.*;

/** Oracle native plan capture: one dedicated connection, one business SELECT, independent bounded evidence reads. */
public final class OraclePlanCollector {
 static boolean certifiedVersion(String product,String version){return product!=null&&product.startsWith("Oracle")&&version!=null&&version.contains("Version 23.7.0.25.01");}
 static com.bitdata.sqlworkbench.execution.model.PlanCapabilities inspect(String id,Connection c,ExplainServiceFactory adapters,Runnable checkpoint)throws SQLException{
  checkpoint.run();var metadata=c.getMetaData();String product=metadata.getDatabaseProductName(),version=metadata.getDatabaseProductVersion();checkpoint.run();
  if(!certifiedVersion(product,version)||!adapters.supports("oracle"))return PlanCapabilityService.unknown(id,"oracle",adapters.supports("oracle")?"SERVER_VERSION_UNVERIFIED":"APPLICATION_CHANNEL_UNSUPPORTED");
  if(!"21.1.0.0.0".equals(metadata.getDriverVersion()))return PlanCapabilityService.unknown(id,"oracle","DRIVER_VERSION_UNVERIFIED");
  String estimatedReason=null,actualReason=null;
  try(Statement st=c.createStatement()){
   st.setQueryTimeout(3);checkpoint.run();try(ResultSet rs=st.executeQuery(TEMP)){
    if(!rs.next()||!"Y".equals(rs.getString(1))||!"SYS$SESSION".equals(rs.getString(2))||rs.next())estimatedReason="PLAN_TABLE_TEMPORARY_CONTRACT_UNVERIFIED";
   }checkpoint.run();
  }catch(SQLException failure){if(transportLost(failure))throw failure;estimatedReason="PLAN_TABLE_ACCESS_UNAVAILABLE";}
  for(String sql:List.of("SELECT prev_sql_id,prev_child_number,prev_exec_id,prev_exec_start FROM v$session WHERE 1=0","SELECT sql_id,child_number,sql_fulltext,executions,end_of_fetch_count,users_executing,child_address,plan_hash_value,loads,invalidations FROM v$sql WHERE 1=0","SELECT id,parent_id,operation,options,cardinality,cost FROM v$sql_plan WHERE 1=0","SELECT id,parent_id,operation,options,cardinality,cost,last_starts,last_output_rows,last_cr_buffer_gets,last_disk_reads,last_elapsed_time FROM v$sql_plan_statistics_all WHERE 1=0")){
   checkpoint.run();try(Statement st=c.createStatement()){st.setQueryTimeout(3);try(ResultSet rs=st.executeQuery(sql)){if(rs.next())actualReason="CAPABILITY_RESULT_SHAPE_UNEXPECTED";}}
   catch(SQLException failure){if(transportLost(failure))throw failure;actualReason="PLAN_STATISTICS_ACCESS_UNAVAILABLE";break;}checkpoint.run();
  }
  var estimated=new com.bitdata.sqlworkbench.execution.model.PlanCapabilities.ModeCapability(estimatedReason==null?"SUPPORTED":"UNKNOWN",estimatedReason);
  var actual=new com.bitdata.sqlworkbench.execution.model.PlanCapabilities.ModeCapability(actualReason==null?"SUPPORTED":"UNKNOWN",actualReason);
  return new com.bitdata.sqlworkbench.execution.model.PlanCapabilities(id,"oracle",product,version,estimated,actual,java.time.Instant.now().toString(),estimatedReason!=null||actualReason!=null,estimatedReason!=null?estimatedReason:actualReason);
 }
 static PlanEvidence.Environment environment(Connection c,int timeout,Runnable checkpoint)throws SQLException{
  checkpoint.run();try(Statement st=c.createStatement()){
   st.setQueryTimeout(Math.max(1,Math.min(3,timeout)));try(ResultSet rs=st.executeQuery("SELECT SYS_CONTEXT('USERENV','DB_NAME'),SYS_CONTEXT('USERENV','SESSION_USER'),SYS_CONTEXT('USERENV','CURRENT_SCHEMA') FROM dual")){
    if(!rs.next())return new PlanEvidence.Environment(null,null,Map.of(),"ENVIRONMENT_CAPTURE_PARTIAL");
    String database=rs.getString(1),user=rs.getString(2),schema=rs.getString(3);checkpoint.run();
    if(rs.next())return new PlanEvidence.Environment(null,null,Map.of(),"ENVIRONMENT_CAPTURE_PARTIAL");
    return new PlanEvidence.Environment(database,user,schema==null?Map.of():Map.of("currentSchema",schema),database==null||user==null?"ENVIRONMENT_CAPTURE_PARTIAL":null);
   }
  }catch(SQLException failure){if(transportLost(failure))throw failure;return new PlanEvidence.Environment(null,null,Map.of(),"ENVIRONMENT_CAPTURE_FAILED");}
 }
 public record Outcome(String submittedSql,String commandSql,String mode,String bindingState,String sqlId,String childNumber,
                       String executionId,String executionStart,OraclePlanRowsParser.Parsed parsed,String cleanup) {}
 @FunctionalInterface public interface Cleanup {String finish(Connection c,List<Statement> statements,boolean transportLost);}
 static final List<String> ACTUAL=List.of("ID","PARENT_ID","OPERATION","OPTIONS","CARDINALITY","COST","LAST_STARTS","LAST_OUTPUT_ROWS","LAST_CR_BUFFER_GETS","LAST_DISK_READS","LAST_ELAPSED_TIME");
 static final List<String> ESTIMATED=List.of("ID","PARENT_ID","OPERATION","OPTIONS","CARDINALITY","COST","BYTES","TIME");
 static final String BINDING="SELECT prev_sql_id,prev_child_number,prev_exec_id,TO_CHAR(prev_exec_start,'YYYY-MM-DD HH24:MI:SS') AS prev_exec_start FROM v$session WHERE sid=TO_NUMBER(SYS_CONTEXT('USERENV','SID'))";
 static final String IDENTITY="SELECT executions,end_of_fetch_count,users_executing,RAWTOHEX(child_address) AS child_address,plan_hash_value,loads,invalidations FROM v$sql WHERE sql_id=? AND child_number=?";
 static final String STATS="SELECT id,parent_id,operation,options,cardinality,cost,last_starts,last_output_rows,last_cr_buffer_gets,last_disk_reads,last_elapsed_time FROM v$sql_plan_statistics_all WHERE sql_id=? AND child_number=? ORDER BY id";
 static final String TEMP="SELECT temporary,duration FROM all_tables WHERE table_name='PLAN_TABLE$' AND owner=(SELECT table_owner FROM all_synonyms WHERE owner='PUBLIC' AND synonym_name='PLAN_TABLE')";
 static void require(boolean v,String reason){if(!v)throw new OraclePlanReadLimits.Limited(reason);}
 static Statement statement(Connection c,List<Statement> owned,IntSupplier timeout,Runnable checkpoint,Consumer<Statement> active)throws SQLException{
  checkpoint.run();Statement s=c.createStatement();owned.add(s);active.accept(s);s.setQueryTimeout(timeout.getAsInt());checkpoint.run();return s;
 }
 static PreparedStatement prepared(Connection c,String sql,List<String> params,List<Statement> owned,IntSupplier timeout,Runnable checkpoint,Consumer<Statement> active)throws SQLException{
  checkpoint.run();PreparedStatement s=c.prepareStatement(sql);owned.add(s);active.accept(s);s.setQueryTimeout(timeout.getAsInt());for(int i=0;i<params.size();i++)s.setString(i+1,params.get(i));checkpoint.run();return s;
 }
 static List<String> one(Statement s,String sql,int columns,Runnable checkpoint)throws Exception{
  try(ResultSet rs=s instanceof PreparedStatement p?p.executeQuery():s.executeQuery(sql)){
   checkpoint.run();require(rs.next(),"CURSOR_EVIDENCE_MISSING");checkpoint.run();var values=new ArrayList<String>();
   for(int i=1;i<=columns;i++)values.add(OraclePlanReadLimits.cell(rs,i,1024,4096,"IDENTITY_FIELD_LIMIT",checkpoint));
   checkpoint.run();require(!rs.next(),"AMBIGUOUS_CURSOR_EVIDENCE");checkpoint.run();return values;
  }
 }
 public static Outcome capture(Connection c,String original,String mode,IntSupplier timeout,Runnable checkpoint,Consumer<Statement> active,Cleanup owner)throws Exception{
  String marker="sdw_"+UUID.randomUUID().toString().replace("-","").substring(0,24),submitted=original,command=null,binding=null,sqlId=null,child=null,exec=null,start=null;
  var owned=new ArrayList<Statement>();OraclePlanRowsParser.Parsed parsed=null;String cleanup="NOT_STARTED";boolean lost=false;
  try{
  require(Set.of("actual","estimated").contains(mode),"INVALID_MODE");require(original!=null&&!original.isBlank(),"SQL_REQUIRED");
  // Caller SELECT guard rejects multiple statements. SQL text is not trimmed or rewritten here.
  require(original.length()<=OraclePlanReadLimits.SQL_CHARS-64&&original.getBytes(java.nio.charset.StandardCharsets.UTF_8).length<=OraclePlanReadLimits.SQL_BYTES-64,"SQL_TEXT_BUDGET_EXCEEDED");
   checkpoint.run();c.setAutoCommit(false);checkpoint.run();
   if(mode.equals("estimated")){
    var temp=one(statement(c,owned,timeout,checkpoint,active),TEMP,2,checkpoint);require(temp.equals(List.of("Y","SYS$SESSION")),"PLAN_TABLE_TEMPORARY_CONTRACT_UNVERIFIED");
    command="EXPLAIN PLAN SET STATEMENT_ID='"+marker+"' FOR "+original;
    statement(c,owned,timeout,checkpoint,active).execute(command);checkpoint.run();
    String planSql="SELECT id,parent_id,operation,options,cardinality,cost,bytes,time FROM plan_table WHERE statement_id=? ORDER BY id";
    try(ResultSet rs=prepared(c,planSql,List.of(marker),owned,timeout,checkpoint,active).executeQuery()){
     var rows=OraclePlanReadLimits.planRows(rs,ESTIMATED,checkpoint);parsed=OraclePlanRowsParser.parse(rows.rows(),mode,"VERIFIED_ESTIMATED");
    }
    binding="VERIFIED_ESTIMATED";
   }else{
    statement(c,owned,timeout,checkpoint,active).execute("ALTER SESSION SET statistics_level=ALL");checkpoint.run();
    submitted="/* "+marker+" */\n"+original;command=submitted;
    // Exactly one business execution. UI maxRows is intentionally absent.
    try(ResultSet rs=statement(c,owned,timeout,checkpoint,active).executeQuery(submitted)){OraclePlanReadLimits.drain(rs,checkpoint);}checkpoint.run();
    var bound=one(statement(c,owned,timeout,checkpoint,active),BINDING,4,checkpoint);
    sqlId=bound.get(0);child=bound.get(1);exec=bound.get(2);start=bound.get(3);
    require(sqlId!=null&&sqlId.matches("[a-z0-9]{13}")&&child!=null&&child.matches("0|[1-9][0-9]*")&&exec!=null&&start!=null,"EXECUTION_BINDING_UNVERIFIED");
    var params=List.of(sqlId,child);var before=one(prepared(c,IDENTITY,params,owned,timeout,checkpoint,active),null,7,checkpoint);
    require(before.subList(0,3).equals(List.of("1","1","0"))&&before.stream().noneMatch(Objects::isNull),"CURSOR_EXECUTION_NOT_EXCLUSIVE_OR_INCOMPLETE");
    String full;try(ResultSet rs=prepared(c,"SELECT sql_fulltext FROM v$sql WHERE sql_id=? AND child_number=?",params,owned,timeout,checkpoint,active).executeQuery()){full=OraclePlanReadLimits.fullSql(rs,checkpoint);}
    require(submitted.equals(full),"FULLTEXT_MISMATCH");
    JsonNode rows;try(ResultSet rs=prepared(c,STATS,params,owned,timeout,checkpoint,active).executeQuery()){rows=OraclePlanReadLimits.planRows(rs,ACTUAL,checkpoint).rows();}
    var after=one(prepared(c,IDENTITY,params,owned,timeout,checkpoint,active),null,7,checkpoint);require(before.equals(after),"CURSOR_CHANGED");
    parsed=OraclePlanRowsParser.parse(rows,mode,"VERIFIED_COMPLETE");binding="VERIFIED_COMPLETE";
   }
   checkpoint.run();
  }catch(SQLException failure){lost=transportLost(failure);throw failure;}
  finally{active.accept(null);cleanup=owner.finish(c,List.copyOf(owned),lost);}
  return new Outcome(submitted,command,mode,binding,sqlId,child,exec,start,parsed,cleanup);
 }
 static boolean transportLost(Throwable failure){for(Throwable x=failure;x!=null;x=x.getCause())if(x instanceof SQLException s&&(s.getSQLState()!=null&&s.getSQLState().startsWith("08")||Set.of(17002,17008,17410,3113,3114,3135,12170).contains(Math.abs(s.getErrorCode()))))return true;return false;}
 /** Dedicated connection is physically discarded; no session setting escapes into another task. */
 public static String close(Connection c,List<Statement> statements,boolean lost){
  String reason=lost?"UNCONFIRMED_CONNECTION_LOST":"CONFIRMED";
  if(!lost)try{c.rollback();}catch(SQLException|RuntimeException e){reason="PLAN_ROLLBACK_UNCONFIRMED";}
  for(int i=statements.size()-1;i>=0;i--)try{statements.get(i).close();}catch(SQLException|RuntimeException e){reason="PLAN_STATEMENT_CLOSE_UNCONFIRMED";}
  try{c.close();}catch(SQLException|RuntimeException e){reason="CONNECTION_CLEANUP_UNCONFIRMED";}return reason;
 }
}
