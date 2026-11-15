package com.bitdata.sqlworkbench.diagnostics.trace;

import com.bitdata.sqlworkbench.connection.*;
import com.bitdata.sqlworkbench.diagnostics.source.*;
import com.bitdata.sqlworkbench.session.WorkspaceIdentity;
import org.junit.jupiter.api.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SourcePreparationContractTest {
    final WorkloadLeaseManager leases=new WorkloadLeaseManager();
    final ConnectionService connections=mock(ConnectionService.class);
    final DiagnosticAdmission admission=mock(DiagnosticAdmission.class);
    final OracleSourcePlanService oracle=mock(OracleSourcePlanService.class);
    final SourceObservationReceipts receipts=mock(SourceObservationReceipts.class);
    final AtomicLong clock=new AtomicLong();
    final AtomicInteger opens=new AtomicInteger(),cancels=new AtomicInteger();
    final CountDownLatch entered=new CountDownLatch(2),queryRelease=new CountDownLatch(1),cancelEntered=new CountDownLatch(1),cancelRelease=new CountDownLatch(1);
    final AtomicBoolean interrupted=new AtomicBoolean();
    SourcePreparationService service;
    final PhysicalSourcePlanService.Request request=new PhysicalSourcePlanService.Request("c","SELECT 1 K FROM dual",List.of(new KeyValue("K","1",KeyValue.Type.DECIMAL)));
    @BeforeEach void setup()throws Exception {
        var config=new ConnectionConfig("c","synthetic","localhost",1521,"db","user","oracle","ref",true,"now","now","SERVICE_NAME",false);
        when(connections.getById("c")).thenReturn(Optional.of(config));when(admission.connectionVersion("c")).thenReturn("version");
        when(connections.acquireOrdinaryWorkload()).thenAnswer(i->leases.acquireOrdinary());
        when(connections.openOrdinaryWithTimeout(anyString(),anyString(),anyLong(),any())).thenAnswer(i->{
            opens.incrementAndGet();var connection=mock(Connection.class);
            when(connection.prepareStatement(anyString())).thenAnswer(j->{
                var statement=mock(PreparedStatement.class);
                doAnswer(k->{cancels.incrementAndGet();cancelEntered.countDown();try{cancelRelease.await(10,TimeUnit.SECONDS);}catch(InterruptedException bad){interrupted.set(true);throw bad;}return null;}).when(statement).cancel();
                return statement;
            });
            return leases.track(connection,i.getArgument(3));
        });
        when(oracle.inspect(any(),anyBoolean(),any(),any())).thenAnswer(i->{
            Connection connection=i.getArgument(2);PreparationJdbc task=i.getArgument(3);
            try(var statement=connection.prepareStatement("SELECT 1 K FROM dual")) {
                task.configure(statement);entered.countDown();
                try{queryRelease.await(10,TimeUnit.SECONDS);}catch(InterruptedException bad){interrupted.set(true);throw bad;}
            }finally{task.clearStatement();}
            return new ComplexSourcePlanService.Plan("UNKNOWN",false,"SYNTHETIC_NOT_CERTIFIED","c",request.sql(),"metadata",List.of(),List.of(),"now");
        });
        service=new SourcePreparationService(connections,admission,oracle,receipts,clock::get);
    }
    @AfterEach void close(){queryRelease.countDown();cancelRelease.countDown();service.shutdown();leases.close();}
    String submit(){return service.submit(new SourcePreparationService.Submit(SourcePreparationService.Kind.COMPLEX,request));}
    void await(java.util.function.BooleanSupplier condition)throws Exception {long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);while(!condition.getAsBoolean()&&System.nanoTime()<end)Thread.sleep(5);assertTrue(condition.getAsBoolean());}

    @Test void cancellationOwnsItsCallbackAndLatePlanCannotPublish()throws Exception {
        String id=submit();await(()->entered.getCount()==1);
        assertTrue(service.cancel(id));assertTrue(cancelEntered.await(2,TimeUnit.SECONDS));
        var progress=service.get(id);assertEquals(TraceStatus.CANCELLED,progress.status());assertNull(progress.plan());assertFalse(progress.lifecycle().settled());
        assertThrows(WorkloadBusyException.class,()->leases.acquireExclusive());
        queryRelease.countDown();await(()->"PENDING".equals(service.get(id).lifecycle().cleanup()));
        assertFalse(service.get(id).lifecycle().settled());
        cancelRelease.countDown();await(()->service.get(id).lifecycle().settled());
        assertEquals("CLOSED",service.get(id).lifecycle().cleanup());assertNull(service.get(id).plan());
        assertEquals(1,opens.get());assertEquals(1,cancels.get());verifyNoInteractions(receipts);assertFalse(interrupted.get());
        long revision=service.get(id).lifecycle().revision();assertEquals(revision,service.get(id).lifecycle().revision());
    }
    @Test void queuedDeadlineAndShutdownCannotOpenAnotherConnection()throws Exception {
        submit();submit();assertTrue(entered.await(2,TimeUnit.SECONDS));String queued=submit();
        clock.set(TimeUnit.SECONDS.toNanos(5));var expired=service.get(queued);
        assertEquals("SOURCE_PREPARATION_BUDGET_EXCEEDED",expired.errorReason());assertEquals("NO_RESOURCES",expired.lifecycle().cleanup());assertTrue(expired.lifecycle().settled());
        service.shutdown();queryRelease.countDown();cancelRelease.countDown();
        await(()->leases.snapshot().ordinaryTickets()==0);assertEquals(2,opens.get());assertFalse(interrupted.get());
        assertEquals(503,assertThrows(WorkspaceIdentity.Failure.class,this::submit).status);
    }
    @Test void realTimerExpiresWithoutPollingAndKeepsBlockedOwner()throws Exception {
        String id=submit();await(()->entered.getCount()==1);
        assertTrue(cancelEntered.await(7,TimeUnit.SECONDS));
        var progress=service.get(id);assertEquals("SOURCE_PREPARATION_BUDGET_EXCEEDED",progress.errorReason());assertFalse(progress.lifecycle().settled());assertNull(progress.plan());
        queryRelease.countDown();cancelRelease.countDown();await(()->service.get(id).lifecycle().settled());
        assertEquals(1,opens.get());assertEquals(1,cancels.get());verifyNoInteractions(receipts);
    }
    @Test void capacityAndScopeAreExplicitWithoutHiddenRetries()throws Exception {
        submit();submit();assertTrue(entered.await(2,TimeUnit.SECONDS));
        String id=null;for(int i=2;i<256;i++)id=submit();
        assertEquals("SOURCE_PREPARATION_CAPACITY_EXHAUSTED",assertThrows(WorkspaceIdentity.Failure.class,this::submit).code);
        assertEquals(2,opens.get());
        doThrow(new WorkspaceIdentity.Failure(409,"CONNECTION_CHANGED")).when(admission).revalidate(any(),eq("c"),eq("version"));
        final String task=id;
        assertEquals("CONNECTION_CHANGED",assertThrows(WorkspaceIdentity.Failure.class,()->service.get(task)).code);
        assertEquals("CONNECTION_CHANGED",assertThrows(WorkspaceIdentity.Failure.class,()->service.cancel(task)).code);
        assertEquals(404,assertThrows(WorkspaceIdentity.Failure.class,()->service.get("other-task")).status);
    }
}
