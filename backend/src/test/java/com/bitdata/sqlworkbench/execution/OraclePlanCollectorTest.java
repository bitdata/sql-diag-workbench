package com.bitdata.sqlworkbench.execution;
import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.sql.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class OraclePlanCollectorTest {
 static void require(boolean v,String reason){if(!v)throw new IllegalStateException(reason);}
 static ResultSet rows(List<List<String>> data)throws Exception{
  ResultSet rs=mock(ResultSet.class);ResultSetMetaData metadata=mock(ResultSetMetaData.class);when(rs.getMetaData()).thenReturn(metadata);when(metadata.getColumnType(anyInt())).thenReturn(Types.VARCHAR);AtomicInteger index=new AtomicInteger(-1);when(rs.next()).thenAnswer(i->index.incrementAndGet()<data.size());when(rs.getString(anyInt())).thenAnswer(i->data.get(index.get()).get((int)i.getArgument(0)-1));when(rs.getCharacterStream(anyInt())).thenAnswer(i->{String value=data.get(index.get()).get((int)i.getArgument(0)-1);return value==null?null:new StringReader(value);});return rs;
 }
 static class Fixture{
  final Connection c=mock(Connection.class);final AtomicInteger business=new AtomicInteger(),identity=new AtomicInteger(),cleanups=new AtomicInteger();final AtomicReference<String> submitted=new AtomicReference<>();final List<String> commands=new ArrayList<>();final String scenario;final JsonNode plan;
  Fixture(String scenario,JsonNode plan)throws Exception{
   this.scenario=scenario;this.plan=plan;
   when(c.createStatement()).thenAnswer(i->{Statement s=mock(Statement.class);when(s.execute(anyString())).thenAnswer(call->{String sql=call.getArgument(0);commands.add(sql);if(sql.startsWith("EXPLAIN")&&scenario.equals("explain-error"))throw new SQLException("SYNTHETIC",null,904);return false;});
    when(s.executeQuery(anyString())).thenAnswer(call->{String sql=call.getArgument(0);commands.add(sql);
     if(sql.equals(OraclePlanCollector.TEMP))return rows(List.of(List.of(scenario.equals("non-temporary")?"N":"Y","SYS$SESSION")));
     if(sql.equals(OraclePlanCollector.BINDING))return rows(List.of(List.of("0123456789abc","0","123","2026-11-12 00:00:00")));
     if(sql.endsWith("WHERE 1=0"))return rows(List.of());
     if(sql.startsWith("SELECT SYS_CONTEXT"))return rows(List.of(List.of("synthetic-db","synthetic-user","synthetic-schema")));
     require(sql.startsWith("/* sdw_"),"UNEXPECTED_BUSINESS_WRAPPER");business.incrementAndGet();submitted.set(sql);
     if(scenario.equals("transport-lost"))throw new SQLException("SYNTHETIC","08006",17002);
     if(scenario.equals("drain-limit"))return java.lang.reflect.Proxy.newProxyInstance(ResultSet.class.getClassLoader(),new Class<?>[]{ResultSet.class},(p,m,a)->{if(m.getName().equals("next"))return true;if(m.getName().equals("close"))return null;throw new IllegalStateException("BUSINESS_CELL_MATERIALIZED");});
     ResultSet rs=mock(ResultSet.class);ResultSetMetaData metadata=mock(ResultSetMetaData.class);when(rs.getMetaData()).thenReturn(metadata);when(metadata.getColumnType(anyInt())).thenReturn(Types.VARCHAR);when(rs.next()).thenReturn(true,true,true,false);return rs;});return s;});
   when(c.prepareStatement(anyString())).thenAnswer(i->{String sql=i.getArgument(0);PreparedStatement s=mock(PreparedStatement.class);
    when(s.executeQuery()).thenAnswer(call->{commands.add(sql);
     if(sql.equals(OraclePlanCollector.IDENTITY)){int n=identity.incrementAndGet();return rows(List.of(List.of(scenario.equals("repeated")?"2":"1","1","0",scenario.equals("cursor-changed")&&n>1?"different":"stable","99","1","0")));}
     if(sql.startsWith("SELECT sql_fulltext"))return rows(List.of(List.of(scenario.equals("text-mismatch")?"DIFFERENT":submitted.get())));
     if(sql.equals(OraclePlanCollector.STATS)&&scenario.equals("permission"))throw new SQLException("SYNTHETIC",null,942);
     List<String> columns=sql.equals(OraclePlanCollector.STATS)?OraclePlanCollector.ACTUAL:OraclePlanCollector.ESTIMATED;
     require(sql.equals(OraclePlanCollector.STATS)||sql.startsWith("SELECT id,parent_id,operation,options,cardinality,cost,bytes,time FROM plan_table"),"UNEXPECTED_OBSERVATION_SQL");
     var values=new ArrayList<List<String>>();for(JsonNode row:plan){var v=new ArrayList<String>();for(String col:columns){JsonNode raw=row.get(col);v.add(scenario.equals("missing-stats")&&col.startsWith("LAST_")?null:raw==null||raw.isNull()?null:raw.asText());}values.add(v);}return rows(values);
    });return s;});
  }
  String cleanup(Connection c,List<Statement> statements,boolean lost){cleanups.incrementAndGet();return OraclePlanCollector.close(c,statements,lost);}
 }
 @org.junit.jupiter.params.ParameterizedTest
 @org.junit.jupiter.params.provider.ValueSource(strings={"actual","estimated","missing-stats","text-mismatch","repeated","cursor-changed","permission","drain-limit","transport-lost","non-temporary","explain-error"})
 void rejectsUnboundEvidenceWithoutReplayingBusinessAndAlwaysCleansUp(String scenario)throws Exception{
  JsonNode plan=new ObjectMapper().readTree("[{\"ID\":\"0\",\"PARENT_ID\":null,\"OPERATION\":\"SELECT STATEMENT\",\"CARDINALITY\":\"1\",\"COST\":\"1\",\"LAST_STARTS\":\"1\",\"LAST_OUTPUT_ROWS\":\"1\",\"LAST_CR_BUFFER_GETS\":\"0\",\"LAST_DISK_READS\":\"0\",\"LAST_ELAPSED_TIME\":\"9007199254740993\"}]");
   Fixture f=new Fixture(scenario,plan);String mode=Set.of("estimated","non-temporary","explain-error").contains(scenario)?"estimated":"actual";String observed=null;OraclePlanCollector.Outcome result=null;
   try{result=OraclePlanCollector.capture(f.c,"-- keep original\nSELECT 1 FROM dual",mode,()->8,()->{},s->{},f::cleanup);}
   catch(OraclePlanReadLimits.Limited e){observed=e.getMessage();}
   catch(SQLException e){observed="SQL_"+Math.abs(e.getErrorCode());}
   String expected=switch(scenario){case "text-mismatch"->"FULLTEXT_MISMATCH";case "repeated"->"CURSOR_EXECUTION_NOT_EXCLUSIVE_OR_INCOMPLETE";case "cursor-changed"->"CURSOR_CHANGED";case "permission"->"SQL_942";case "drain-limit"->"DATA_STREAM_LIMIT_EXCEEDED";case "transport-lost"->"SQL_17002";case "non-temporary"->"PLAN_TABLE_TEMPORARY_CONTRACT_UNVERIFIED";case "explain-error"->"SQL_904";default->null;};
   require(Objects.equals(observed,expected),scenario+"_WRONG_REASON_"+observed);require(f.cleanups.get()==1,"CLEANUP_OWNER_NOT_CALLED");require(f.business.get()==(mode.equals("actual")?1:0),"BUSINESS_EXECUTION_REPLAY_OR_ESTIMATED_EXECUTION");verify(f.c).close();
   if(scenario.equals("transport-lost"))verify(f.c,never()).rollback();else verify(f.c).rollback();
   if(result!=null){require(result.cleanup().equals("CONFIRMED"),"CLEANUP_UNKNOWN");require(result.parsed().plan()!=null,"PLAN_MISSING");if(scenario.equals("missing-stats"))require(result.parsed().degraded(),"MISSING_STATS_SILENT");if(mode.equals("actual"))require(result.submittedSql().endsWith("-- keep original\nSELECT 1 FROM dual")&&result.bindingState().equals("VERIFIED_COMPLETE"),"ORIGINAL_OR_BINDING_LOST");}
 }
 @org.junit.jupiter.api.Test void invalidInputAfterOwnershipTransferStillCloses()throws Exception{
  Fixture f=new Fixture("actual",new ObjectMapper().createArrayNode());
  org.junit.jupiter.api.Assertions.assertThrows(OraclePlanReadLimits.Limited.class,()->OraclePlanCollector.capture(f.c,"SELECT 1 FROM dual","invalid",()->8,()->{},s->{},f::cleanup));
  verify(f.c).close();org.junit.jupiter.api.Assertions.assertEquals(1,f.cleanups.get());
 }
}
