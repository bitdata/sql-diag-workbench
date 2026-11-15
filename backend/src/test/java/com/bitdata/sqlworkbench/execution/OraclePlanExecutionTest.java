package com.bitdata.sqlworkbench.execution;

import com.bitdata.sqlworkbench.connection.*;
import com.bitdata.sqlworkbench.execution.model.*;
import com.bitdata.sqlworkbench.sql.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class OraclePlanExecutionTest {
    final ConnectionService service=mock(ConnectionService.class);
    final WorkloadLeaseManager leases=new WorkloadLeaseManager();
    final ConnectionConfig config=new ConnectionConfig("native","synthetic","localhost",1521,"synthetic","synthetic","oracle",null,true,"created","updated","SERVICE_NAME",true);
    final ExplainServiceFactory adapters=new ExplainServiceFactory(List.of(new OraclePlanAdapter()));
    OraclePlanCollectorTest.Fixture jdbc;
    DatabaseMetaData metadata;
    ExecutionEngine engine;
    @BeforeEach void setup() throws Exception {
        jdbc=new OraclePlanCollectorTest.Fixture("actual",new ObjectMapper().readTree("[{\"ID\":\"0\",\"PARENT_ID\":null,\"OPERATION\":\"SELECT STATEMENT\",\"CARDINALITY\":\"1\",\"COST\":\"1\",\"LAST_STARTS\":\"1\",\"LAST_OUTPUT_ROWS\":\"3\",\"LAST_ELAPSED_TIME\":\"9007199254740993\"}]"));
        metadata=mock(DatabaseMetaData.class);when(jdbc.c.getMetaData()).thenReturn(metadata);
        when(metadata.getDatabaseProductName()).thenReturn("Oracle Database 23ai Free");
        when(metadata.getDatabaseProductVersion()).thenReturn("Oracle Database 23ai Free\nVersion 23.7.0.25.01");
        when(metadata.getDriverVersion()).thenReturn("21.1.0.0.0");
        when(service.getById("native")).thenReturn(Optional.of(config));
        when(service.acquireOrdinaryWorkload()).thenAnswer(i->leases.acquireOrdinary());
        when(service.openOrdinaryWithTimeout(eq("native"),anyString(),anyLong(),any())).thenAnswer(i->leases.track(jdbc.c,i.getArgument(3)));
        var strategies=DatabaseStrategyFactory.withBuiltinStrategies();
        engine=new ExecutionEngine(service,adapters,strategies,new SelectOnlyGuard(new SqlDialectResolver(strategies)),30,100);
    }
    @AfterEach void close(){engine.shutdown();leases.close();}
    static void await(java.util.function.BooleanSupplier done)throws Exception{
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
        while(!done.getAsBoolean()&&System.nanoTime()<end)Thread.sleep(5);
        assertTrue(done.getAsBoolean(),"Timed out awaiting synthetic driver state");
    }
    @ParameterizedTest @ValueSource(strings={"actual","estimated"})
    void nativeEnginePublishesIndependentEvidenceAndSettlesSharedOwner(String mode)throws Exception{
        String sql="SELECT  1 FROM dual";String id=engine.analyze(sql,"native",null,null,mode,null);
        await(()->engine.getStatus(id).status().isTerminal());var result=engine.getStatus(id);
        assertEquals(ExecutionStatus.COMPLETED,result.status(),result.errorReason());assertNotNull(result.plan());
        assertEquals(sql,result.planCapture().submittedSql());assertEquals("ORACLE_PLAN_ROWS",result.planCapture().details().channel());
        assertEquals(mode.equals("actual")?1:0,jdbc.business.get());
        assertEquals("UNKNOWN",result.plan().metrics().actualRows().state());
        assertEquals("VERIFIED",engine.resolvePlanObservation(id,sql,"native",null).sourceState());
        await(()->leases.snapshot().ordinaryTickets()==0);
        var wire=new ObjectMapper().valueToTree(engine.ordinaryResponse(id));assertTrue(wire.path("lifecycle").path("settled").asBoolean());
        verify(jdbc.c).rollback();verify(jdbc.c).close();
    }
    @Test void receiptPersistenceCannotExposeFinalCaptureWithoutItsPlan()throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var store=mock(PlanObservationReceiptStore.class);
        when(store.save(any(),any(),any(),any())).thenAnswer(i->{entered.countDown();assertTrue(release.await(8,TimeUnit.SECONDS));return new PlanObservationReceiptStore.Result("SAVED","synthetic-receipt",null);});
        ReflectionTestUtils.setField(engine,"observationReceiptStore",store);
        try {
            String id=engine.analyze("SELECT 1 FROM dual","native");assertTrue(entered.await(4,TimeUnit.SECONDS));
            var pending=engine.getStatus(id);
            assertEquals(ExecutionStatus.RUNNING,pending.status());assertNull(pending.plan());
            assertNull(pending.planCapture().details(),"Pending result must not expose completed native evidence without a tree");
            assertEquals("NOT_STARTED",pending.planCapture().parseStatus());
            release.countDown();await(()->engine.getStatus(id).status().isTerminal());
            assertNotNull(engine.getStatus(id).planCapture().details());assertNotNull(engine.getStatus(id).plan());
        } finally { release.countDown(); }
    }
    @Test void untestedDriverIsUnknownAndNeverExecutesBusinessSql()throws Exception{
        when(metadata.getDriverVersion()).thenReturn("untested");
        String id=engine.analyze("SELECT 1 FROM dual","native");await(()->engine.getStatus(id).status().isTerminal());
        assertEquals("DRIVER_VERSION_UNVERIFIED",engine.getStatus(id).errorReason());assertEquals(0,jdbc.business.get());
    }
    @Test void timedOutMetadataRetainsOwnerAndCannotRacePhysicalClose()throws Exception{
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        when(jdbc.c.getMetaData()).thenAnswer(i->{entered.countDown();assertTrue(release.await(8,TimeUnit.SECONDS));return metadata;});
        ReflectionTestUtils.setField(engine,"metadataBudgetMillis",100L);
        try{
            String id=engine.analyze("SELECT 1 FROM dual","native");assertTrue(entered.await(2,TimeUnit.SECONDS));
            await(()->engine.getStatus(id).status().isTerminal());
            verify(jdbc.c,never()).close();assertEquals(1,leases.snapshot().ordinaryTickets());
            assertEquals(false,engine.shutdownSnapshot().get("drained"));
            release.countDown();await(()->leases.snapshot().ordinaryTickets()==0);assertEquals(0,jdbc.business.get());
        }finally{release.countDown();}
    }
    @Test void capabilityCloseRetryReleasesNetworkRegistrationAndDrains()throws Exception{
        var closes=new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(i->{if(closes.incrementAndGet()==1)throw new SQLException("Synthetic close failure");return null;}).when(jdbc.c).close();
        var capabilities=new PlanCapabilityService(service,adapters);
        try{
            var capability=capabilities.get("native");assertEquals("UNKNOWN",capability.actual().state());
            assertEquals("CAPABILITY_CLEANUP_UNCONFIRMED",capability.errorReason());
            await(()->Boolean.TRUE.equals(capabilities.shutdownSnapshot().get("drained")));
            assertEquals(0,leases.snapshot().ordinaryTickets());assertTrue(closes.get()>=2);
            var network=(OracleNetworkTimeoutExecutor)ReflectionTestUtils.getField(capabilities,"network");
            assertEquals(0,network.snapshot().openRegistrations());
        }finally{capabilities.close();}
    }
    @Test void cleanupFailureStaysObservableAfterPhysicalOwnersHaveSettled()throws Exception{
        doThrow(new SQLException("Synthetic rollback failure")).when(jdbc.c).rollback();
        String id=engine.analyze("SELECT 1 FROM dual","native");await(()->engine.getStatus(id).status().isTerminal());
        await(()->leases.snapshot().ordinaryTickets()==0);
        var wire=new ObjectMapper().valueToTree(engine.ordinaryResponse(id));
        assertTrue(wire.path("lifecycle").path("settled").asBoolean());
        assertEquals("CLOSED",wire.path("lifecycle").path("cleanup").asText());
        assertEquals("PLAN_ROLLBACK_UNCONFIRMED",wire.path("lifecycle").path("errorReason").asText());
        assertEquals("UNKNOWN",wire.path("lifecycle").path("serverExecution").asText());
    }
}
