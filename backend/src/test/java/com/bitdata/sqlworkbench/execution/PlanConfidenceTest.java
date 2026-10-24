package com.bitdata.sqlworkbench.execution;

import com.bitdata.sqlworkbench.connection.*;
import com.bitdata.sqlworkbench.execution.model.*;
import com.bitdata.sqlworkbench.sql.SelectOnlyGuard;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlanConfidenceTest {
    private final PostgreSqlPlanAdapter pg=new PostgreSqlPlanAdapter();

    @Test void exactDecimalsZeroMissingAndNotExecutedAreDistinct() throws Exception {
        var parsed=pg.parseDetailed("""
            [{"Plan":{"Node Type":"Result","Plan Rows":9007199254740993,"Actual Rows":0,
            "Actual Loops":1,"Actual Total Time":0.123456789012345678901,
            "Plans":[{"Node Type":"Result","Actual Rows":0,"Actual Loops":0}]}}]
            ""","postgresql");
        assertEquals("COMPLETE",parsed.status());
        var metrics=parsed.plan().metrics();
        assertEquals("9007199254740993",metrics.estimatedRows().value());
        assertEquals("0.123456789012345678901",metrics.actualDurationMs().value());
        assertEquals("0",metrics.actualRows().value());
        var child=parsed.plan().children().get(0).metrics();
        assertEquals("NOT_EXECUTED",child.actualRows().state());
        assertEquals("0",child.loops().value());
        assertEquals("NOT_COLLECTED",child.estimatedRows().state());
        assertThrows(UnsupportedOperationException.class,() -> parsed.plan().children().clear());
        String json=new ObjectMapper().writeValueAsString(parsed.plan());
        var roundtrip=new ObjectMapper().readValue(json,ExecutionResult.PlanNode.class);
        assertEquals(metrics,roundtrip.metrics());
        assertEquals("PARTIAL",pg.parseDetailed("[{\"Plan\":{\"Node Type\":\"Result\",\"Plan Rows\":-1}}]","postgresql").status());
        assertEquals("FAILED",pg.parseDetailed("invalid","postgresql").status());
    }

    @Test void mysqlSyntheticCountsAndUnknownOutputAreObservable() {
        var mysql=new MySqlPlanAdapter();
        var parsed=mysql.parseDetailed("""
            {"query_block":{"table":{"table_name":"t","access_type":"ALL","rows_examined_per_scan":12,
            "actual_rows":42,"attached_condition":"t.id > 0","cost_info":{"prefix_cost":"0.123456789012345678901"}}}}
            ""","mysql");
        assertEquals("NOT_COLLECTED",parsed.plan().metrics().actualRows().state());
        assertEquals("UNKNOWN",parsed.plan().children().get(0).metrics().actualRows().state());
        assertEquals("SYNTHETIC_NODE",parsed.plan().children().get(0).metrics().actualRows().errorReason());
        assertEquals("0.123456789012345678901",parsed.plan().metrics().totalCost().value());
        assertEquals("PARTIAL",mysql.parseDetailed("{\"query_block\":{\"new_wrapper\":{}}}","mysql").status());
        assertThrows(IllegalArgumentException.class,() -> new ExplainServiceFactory(List.of(pg,mysql)).get("sqlserver"));
        var timingOff=pg.parse("[{\"Plan\":{\"Node Type\":\"Result\",\"Actual Rows\":3,\"Actual Loops\":1}}]","postgresql");
        assertEquals("AVAILABLE",timingOff.metrics().actualRows().state());
        assertEquals("NOT_COLLECTED",timingOff.metrics().actualDurationMs().state());
    }

    @Test void metadataFailureReturnsUnknownRatherThanSupported() throws Exception {
        var connections=mock(ConnectionService.class); var conn=mock(Connection.class);
        when(connections.getById("c")).thenReturn(Optional.of(new ConnectionConfig("c","test","localhost",5432,"db","u","postgresql",null,false,"","","direct",false)));
        when(connections.openJdbcConnection("c")).thenReturn(conn);
        when(conn.getMetaData()).thenThrow(new SQLException("metadata denied"));
        var result=new PlanCapabilityService(connections,new ExplainServiceFactory(List.of(pg))).get("c");
        assertEquals("UNKNOWN",result.estimated().state());
        assertEquals("CAPABILITY_PROBE_FAILED",result.errorReason());
        assertTrue(result.degraded());
        verify(conn,never()).createStatement();
    }

    @Test @SuppressWarnings({"unchecked","rawtypes"})
    void resolverRejectsRunningExecuteMissingPlanAndLegacyCapture() throws Exception {
        var engine=new ExecutionEngine(mock(ConnectionService.class),new ExplainServiceFactory(List.of(pg)),mock(DatabaseStrategyFactory.class),mock(SelectOnlyGuard.class),30,100);
        try {
            var contexts=(Map<String,Object>)org.springframework.test.util.ReflectionTestUtils.getField(engine,"executions");
            Class<?> contextClass=Class.forName(ExecutionEngine.class.getName()+"$ExecutionContext");
            Class<? extends Enum> kind=(Class<? extends Enum>)Class.forName(ExecutionEngine.class.getName()+"$Kind");
            var constructor=contextClass.getDeclaredConstructor(String.class,String.class,String.class,kind,String.class); constructor.setAccessible(true);
            var plan=pg.parse("[{\"Plan\":{\"Node Type\":\"Result\"}}]","postgresql");
            for(String scenario:List.of("running","execute","no-plan","legacy")) {
                Object context=constructor.newInstance(scenario,"SELECT 1","c",Enum.valueOf(kind,scenario.equals("execute")?"EXECUTE":"ANALYZE"),"estimated");
                contexts.put(scenario,context);
                var result=new ExecutionResult(scenario,ExecutionStatus.COMPLETED,null,null,0,null,scenario.equals("no-plan")?null:plan,null,null);
                var published=new ExecutionEngine.Published(scenario.equals("running")?ExecutionStatus.RUNNING:ExecutionStatus.COMPLETED,result);
                org.springframework.test.util.ReflectionTestUtils.setField(context,"published",published);
                String expected=switch(scenario) {case "execute" -> "MISMATCH"; case "legacy" -> "UNVERIFIED"; default -> "UNAVAILABLE";};
                assertEquals(expected,engine.resolvePlanObservation(scenario,"SELECT 1","c",null).sourceState(),scenario);
            }
        } finally { engine.shutdown(); }
    }

    @Test void retainedServerObservationBindsIdentityAndExpiresMonotonically() throws Exception {
        var connections=mock(ConnectionService.class);
        var conn=mock(Connection.class); var stmt=mock(Statement.class); var rs=mock(ResultSet.class);
        var metadata=mock(DatabaseMetaData.class);
        when(metadata.getDatabaseProductName()).thenReturn("PostgreSQL");
        when(metadata.getDatabaseProductVersion()).thenReturn("16.14");
        when(conn.getMetaData()).thenReturn(metadata); when(conn.createStatement()).thenReturn(stmt);
        when(connections.getById("c")).thenReturn(Optional.of(new ConnectionConfig("c","test","localhost",5432,"db","user","postgresql",null,false,"","","direct",false)));
        when(connections.openJdbcConnection("c")).thenReturn(conn);
        when(stmt.executeQuery(anyString())).thenReturn(rs); when(rs.next()).thenReturn(true,false);
        when(rs.getString(1)).thenReturn("[{\"Plan\":{\"Node Type\":\"Result\",\"Plan Rows\":1}}]");
        var ticker=new AtomicLong(1);
        var engine=new ExecutionEngine(connections,new ExplainServiceFactory(List.of(pg)),new DatabaseStrategyFactory(Map.of("pg",new PostgreSqlStrategy())),mock(SelectOnlyGuard.class),30,100,ticker::get);
        try {
            var id=engine.analyze("SELECT 1","c",5,100,null,"session");
            long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            while(!engine.getStatus(id).status().isTerminal() && System.nanoTime()<deadline) Thread.sleep(5);
            var result=engine.getStatus(id);
            assertEquals(ExecutionStatus.COMPLETED,result.status(),result.error());
            assertEquals("estimated",result.planCapture().attemptedMode());
            verify(stmt).executeQuery("EXPLAIN (ANALYZE false, FORMAT JSON) SELECT 1");
            var snapshot=engine.resolvePlanObservation(id,"SELECT 1","c","session");
            assertEquals("VERIFIED",snapshot.sourceState());
            assertEquals("MISMATCH",engine.resolvePlanObservation(id,"SELECT 2","c","session").sourceState());
            assertEquals("MISMATCH",engine.resolvePlanObservation(id,"SELECT 1","c","other").sourceState());
            assertEquals("MISMATCH",engine.resolvePlanObservation(id,"SELECT 1","other","session").sourceState());
            assertEquals("UNVERIFIED",engine.resolvePlanObservation(null,"SELECT 1","c","session").sourceState());
            ticker.addAndGet(java.util.concurrent.TimeUnit.MINUTES.toNanos(5));
            assertEquals("EXPIRED",engine.resolvePlanObservation(id,"SELECT 1","c","session").sourceState());
            engine.removeExpired();
            assertEquals("UNAVAILABLE",engine.resolvePlanObservation(id,"SELECT 1","c","session").sourceState());
            assertNotNull(snapshot.result().plan());
            assertEquals(result.planCapture(),result.withHistory(new ExecutionResult.Persistence("saved",null)).planCapture());
        } finally { engine.shutdown(); }
    }

    @Test void invalidModesNeverEnterEngineAndMysqlActualNeverOpensJdbc() throws Exception {
        var engine=mock(ExecutionEngine.class);
        var controller=new ExecutionController(engine);
        for(String mode:List.of("","Actual","ACTUAL","other")) {
            var response=controller.analyze(new ExecutionRequest("SELECT 1","c",null,null,mode));
            assertEquals(400,response.getStatusCode().value());
            assertEquals("INVALID_ANALYZE_MODE",((Map<?,?>)response.getBody()).get("errorReason"));
        }
        verifyNoInteractions(engine);
        for(String dialect:List.of("mysql","oracle","sqlserver","unknown")) {
        var connections=mock(ConnectionService.class);
        when(connections.getById("c")).thenReturn(Optional.of(new ConnectionConfig("c","test","localhost",3306,"db","u",dialect,null,false,"","","direct",false)));
        var real=new ExecutionEngine(connections,new ExplainServiceFactory(List.of(new MySqlPlanAdapter())),mock(DatabaseStrategyFactory.class),mock(SelectOnlyGuard.class),30,100);
        try {
            var id=real.analyze("SELECT 1","c",5,100,"mysql".equals(dialect)?"actual":"estimated");
            long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            while(!real.getStatus(id).status().isTerminal() && System.nanoTime()<deadline) Thread.sleep(5);
            var result=real.getStatus(id);
            assertEquals("APPLICATION_CHANNEL_UNSUPPORTED",result.errorReason());
            assertNull(result.planCapture().attemptedMode());
            verify(connections,never()).openJdbcConnection(anyString());
        } finally { real.shutdown(); }
        }
    }
}
