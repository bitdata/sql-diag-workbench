package com.bitdata.sqlworkbench.execution;

import com.bitdata.sqlworkbench.connection.*;
import com.bitdata.sqlworkbench.execution.model.*;
import com.bitdata.sqlworkbench.session.*;
import com.bitdata.sqlworkbench.sql.SelectOnlyGuard;
import com.bitdata.sqlworkbench.sql.decompose.RuleTestDeps;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.io.StringReader;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class SqlServerExecutionEngineTest {
    final ConnectionService service=mock(ConnectionService.class);
    final WorkloadLeaseManager leases=new WorkloadLeaseManager();
    final ConnectionConfig cfg=new ConnectionConfig("native","test","localhost",1433,"db","test","sqlserver",null,true,"created","updated","direct",true);
    ExecutionEngine engine;
    Statement query;
    Connection connection;
    @BeforeEach void setup() throws Exception {
        when(service.getById("native")).thenReturn(Optional.of(cfg));
        when(service.acquireOrdinaryWorkload()).thenAnswer(i->leases.acquireOrdinary());
        engine=new ExecutionEngine(service,new ExplainServiceFactory(List.of(new SqlServerPlanParser())),DatabaseStrategyFactory.withBuiltinStrategies(),new SelectOnlyGuard(new com.bitdata.sqlworkbench.sql.SqlDialectResolver(DatabaseStrategyFactory.withBuiltinStrategies())),30,100);
        connection=mock(Connection.class);var metadata=mock(DatabaseMetaData.class);
        when(connection.getMetaData()).thenReturn(metadata);when(metadata.getDatabaseProductName()).thenReturn("Microsoft SQL Server");
        when(metadata.getDatabaseProductVersion()).thenReturn("17.00.800");when(metadata.getUserName()).thenReturn("test");when(connection.getCatalog()).thenReturn("db");
        var capability=mock(Statement.class);var cap=mock(ResultSet.class);
        when(capability.executeQuery(anyString())).thenReturn(cap);when(cap.next()).thenReturn(true);
        when(cap.getString("product_version")).thenReturn("17.0.800.3");when(cap.getInt("compatibility_level")).thenReturn(170);
        var environment=mock(Statement.class);var env=mock(ResultSet.class);var envMeta=mock(ResultSetMetaData.class);
        when(environment.executeQuery(anyString())).thenReturn(env);when(env.next()).thenReturn(true);when(env.getMetaData()).thenReturn(envMeta);
        when(envMeta.getColumnCount()).thenReturn(1);when(envMeta.getColumnLabel(1)).thenReturn("compatibility_level");when(env.getString(1)).thenReturn("170");
        var setup=mock(Statement.class);query=mock(Statement.class);
        when(connection.createStatement()).thenReturn(capability,environment,setup,query);
        when(service.openOrdinaryWithTimeout(eq("native"),anyString(),anyLong(),any())).thenAnswer(i->{
            WorkloadLeaseManager.Ticket held=i.getArgument(3);doAnswer(close->{held.release();return null;}).when(connection).close();return connection;
        });
        var xml=mock(ResultSet.class);var meta=mock(ResultSetMetaData.class);
        when(query.execute(anyString())).thenReturn(true);when(query.getResultSet()).thenReturn(xml);when(query.getUpdateCount()).thenReturn(-1);
        when(xml.next()).thenReturn(true,false);when(xml.getMetaData()).thenReturn(meta);
        when(meta.getColumnCount()).thenReturn(1);when(meta.getColumnLabel(1)).thenReturn("Microsoft SQL Server 2005 XML Showplan");
        when(xml.getCharacterStream(1)).thenReturn(new StringReader(SqlServerPlanParserTest.PLAN));
    }
    @AfterEach void finish() {engine.shutdown();leases.close();}
    ExecutionResult terminal(String id) throws Exception {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(System.nanoTime()<until) {var r=engine.getStatus(id);if(r.status().isTerminal())return r;Thread.sleep(5);}
        throw new AssertionError("no terminal result");
    }
    @Test void productionNativeBranchPreservesOriginalAndClosesBeforeComplete() throws Exception {
        String sql="SELECT  1 AS k";var id=engine.analyze(sql,"native");var r=terminal(id);
        assertEquals(ExecutionStatus.COMPLETED,r.status(),r.errorReason());assertNotNull(r.plan());
        assertEquals(sql,r.planCapture().submittedSql());assertEquals("SQLSERVER_XML",r.planCapture().details().channel());
        assertEquals("17.0.800.3",r.planCapture().serverVersion());assertTrue(r.degraded());
        assertNotNull(r.planCapture().details().nativeEvidence());verify(connection).close();verify(query).execute(sql);
        assertEquals("VERIFIED",engine.resolvePlanObservation(id,sql,"native",null).sourceState());
        assertEquals("MISMATCH",engine.resolvePlanObservation(id,"SELECT 1","native",null).sourceState());
    }
    @Test void cancelReturnsBeforeBlockedDriverAndLeasePinsBothWorkers() throws Exception {
        var mainEntered=new CountDownLatch(1);var mainRelease=new CountDownLatch(1);
        var cancelEntered=new CountDownLatch(1);var cancelRelease=new CountDownLatch(1);
        when(query.execute(anyString())).thenAnswer(i->{mainEntered.countDown();assertTrue(mainRelease.await(5,TimeUnit.SECONDS));return true;});
        doAnswer(i->{cancelEntered.countDown();assertTrue(cancelRelease.await(5,TimeUnit.SECONDS));return null;}).when(query).cancel();
        try {
            var id=engine.analyze("SELECT 1","native");assertTrue(mainEntered.await(5,TimeUnit.SECONDS));
            long start=System.nanoTime();assertTrue(engine.cancel(id));assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)<500);
            assertEquals(ExecutionStatus.CANCELLED,engine.getStatus(id).status());assertTrue(cancelEntered.await(2,TimeUnit.SECONDS));
            assertEquals(1,leases.snapshot().ordinaryTickets());mainRelease.countDown();
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
            while(System.nanoTime()<until) {try {verify(connection).close();break;}catch(AssertionError waiting){Thread.sleep(5);}}
            verify(connection).close();assertEquals(1,leases.snapshot().ordinaryTickets());
            cancelRelease.countDown();until=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
            while(leases.snapshot().ordinaryTickets()!=0&&System.nanoTime()<until)Thread.sleep(5);
            assertEquals(0,leases.snapshot().ordinaryTickets());
        } finally {mainRelease.countDown();cancelRelease.countDown();}
    }
    @Test void changedConfigurationCannotUseRetainedLiveObservation() throws Exception {
        var id=engine.analyze("SELECT 1","native");assertEquals(ExecutionStatus.COMPLETED,terminal(id).status());
        var changed=new ConnectionConfig(cfg.id(),cfg.name(),cfg.host(),cfg.port(),cfg.database(),cfg.username(),cfg.dialect(),"new-ref",cfg.readonly(),cfg.createdAt(),cfg.updatedAt(),cfg.connectionType(),cfg.trustServerCertificate());
        when(service.getById("native")).thenReturn(Optional.of(changed));
        assertEquals("MISMATCH",engine.resolvePlanObservation(id,"SELECT 1","native",null).sourceState());
        assertEquals("PLAN_OWNER_CHANGED",engine.getStatus(id).errorReason());assertFalse(engine.cancel(id));
    }
    @Test void equalCredentialStateRecordsAreSameOwnerAcrossIndependentReads() throws Exception {
        when(service.getById("native")).thenAnswer(i->Optional.of(cfg.withCredentials(new KeychainStore.State("STORED",null,false))));
        var result=terminal(engine.analyze("SELECT 1","native"));
        assertEquals(ExecutionStatus.COMPLETED,result.status(),result.errorReason());
        assertNotNull(result.plan());
    }
    @Test void recreatedSessionInstanceCannotInheritObservation() throws Exception {
        var sessions=mock(SessionStore.class);ReflectionTestUtils.setField(engine,"sessionStore",sessions);
        var now=Instant.now();
        when(sessions.findById("session")).thenReturn(Optional.of(new Session("session","q","SELECT 1","sqlserver","native",null,null,now,now,"legacy",0,"instance-a")));
        var id=engine.analyze("SELECT 1","native",null,null,"estimated","session");assertEquals(ExecutionStatus.COMPLETED,terminal(id).status());
        when(sessions.findById("session")).thenReturn(Optional.of(new Session("session","q","SELECT 1","sqlserver","native",null,null,now,now,"legacy",0,"instance-b")));
        assertEquals("MISMATCH",engine.resolvePlanObservation(id,"SELECT 1","native","session").sourceState());
    }
    @Test void missingProvidedSessionFailsBeforeOpeningButDetachedOriginalIsAllowed() throws Exception {
        var r=terminal(engine.analyze("SELECT 1","native",null,null,"estimated","missing"));
        assertEquals(ExecutionStatus.FAILED,r.status());assertEquals("SESSION_INSTANCE_CHANGED",r.errorReason());
        verify(service,never()).openOrdinaryWithTimeout(anyString(),anyString(),anyLong(),any());
    }
    @Test void noLateOriginalAfterBudgetExpiresDuringOpen() throws Exception {
        when(service.openOrdinaryWithTimeout(eq("native"),anyString(),anyLong(),any())).thenAnswer(i->{
            var executions=(Map<?,?>)ReflectionTestUtils.getField(engine,"executions");
            for(var context:executions.values())ReflectionTestUtils.setField(context,"deadlineNanos",System.nanoTime()-1);
            WorkloadLeaseManager.Ticket held=i.getArgument(3);doAnswer(close->{held.release();return null;}).when(connection).close();return connection;
        });
        var r=terminal(engine.analyze("SELECT 1","native"));assertEquals(ExecutionStatus.TIMED_OUT,r.status());
        verify(query,never()).execute(anyString());
    }
    @Test void queueInclusiveTimerPublishesBeforeStalledOpenAndQueuedTaskNeverOpens() throws Exception {
        ReflectionTestUtils.setField(engine,"nativeBudgetMillis",300L);
        var entered=new CountDownLatch(4);var release=new CountDownLatch(1);var opens=new java.util.concurrent.atomic.AtomicInteger();
        when(service.openOrdinaryWithTimeout(eq("native"),anyString(),anyLong(),any())).thenAnswer(i->{opens.incrementAndGet();entered.countDown();release.await(5,TimeUnit.SECONDS);var held=(WorkloadLeaseManager.Ticket)i.getArgument(3);Connection late=mock(Connection.class);doAnswer(close->{held.release();return null;}).when(late).close();return late;});
        try {
            var ids=new ArrayList<String>();for(int i=0;i<5;i++)ids.add(engine.analyze("SELECT 1","native"));
            assertTrue(entered.await(2,TimeUnit.SECONDS));
            for(String id:ids)assertEquals(ExecutionStatus.TIMED_OUT,terminal(id).status());
            assertEquals(4,opens.get());assertEquals(4,leases.snapshot().ordinaryTickets());
            release.countDown();long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
            while(leases.snapshot().ordinaryTickets()!=0&&System.nanoTime()<until)Thread.sleep(5);
            assertEquals(0,leases.snapshot().ordinaryTickets());assertEquals(4,opens.get());verify(query,never()).execute(anyString());
        } finally {release.countDown();}
    }
    @Test void metadataDeadlineRetainsItsWorkerAndDoesNotIssueSqlAfterTimeout() throws Exception {
        ReflectionTestUtils.setField(engine,"metadataBudgetMillis",50L);
        var metadata=connection.getMetaData();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        when(metadata.getDatabaseProductName()).thenAnswer(i->{entered.countDown();release.await(5,TimeUnit.SECONDS);return "Microsoft SQL Server";});
        try {
            var id=engine.analyze("SELECT 1","native");assertTrue(entered.await(2,TimeUnit.SECONDS));
            var r=terminal(id);assertEquals(ExecutionStatus.TIMED_OUT,r.status());assertEquals("PLAN_METADATA_BUDGET_EXCEEDED",r.errorReason());
            assertTrue(leases.snapshot().ordinaryTickets()>0);release.countDown();
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(leases.snapshot().ordinaryTickets()!=0&&System.nanoTime()<until)Thread.sleep(5);
            assertEquals(0,leases.snapshot().ordinaryTickets());verify(connection,never()).createStatement();
        } finally {release.countDown();}
    }
}
