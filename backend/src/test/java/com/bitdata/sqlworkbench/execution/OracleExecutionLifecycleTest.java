package com.bitdata.sqlworkbench.execution;

import com.bitdata.sqlworkbench.connection.*;
import com.bitdata.sqlworkbench.sql.*;
import com.bitdata.sqlworkbench.execution.model.ExecutionStatus;
import org.junit.jupiter.api.Test;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real engine and lease manager; every JDBC operation is synthetic and bounded. */
class OracleExecutionLifecycleTest {
    static void eventually(BooleanSupplier condition) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);
        while(!condition.getAsBoolean()&&System.nanoTime()<end)Thread.sleep(5);
        assertTrue(condition.getAsBoolean(),"local lifecycle did not settle within four seconds");
    }
    static void await(CountDownLatch gate) throws InterruptedException {assertTrue(gate.await(4,TimeUnit.SECONDS));}
    static ConnectionConfig config(String dialect,String revision) {
        return new ConnectionConfig("synthetic","synthetic","unused",1,"unused","unused",dialect,"unused",true,"",revision,"service_name",false);
    }
    static Map<?,?> observation(ExecutionEngine engine,String id) {return (Map<?,?>)engine.ordinaryResponse(id);}
    static Map<?,?> lifecycle(ExecutionEngine engine,String id) {return (Map<?,?>)observation(engine,id).get("lifecycle");}
    static ThreadPoolExecutor pool(ExecutionEngine engine)throws Exception {
        var field=ExecutionEngine.class.getDeclaredField("executor");field.setAccessible(true);return (ThreadPoolExecutor)field.get(engine);
    }
    static final class Fixture implements AutoCloseable {
        final WorkloadLeaseManager leases=new WorkloadLeaseManager();
        final AtomicReference<ConnectionConfig> current=new AtomicReference<>(config("oracle","one"));
        final AtomicReference<Executor> network=new AtomicReference<>();
        final AtomicInteger networkMillis=new AtomicInteger(),querySeconds=new AtomicInteger(),executes=new AtomicInteger(),opens=new AtomicInteger(),closes=new AtomicInteger();
        final CountDownLatch entered=new CountDownLatch(1),releaseQuery=new CountDownLatch(1),signalEntered=new CountDownLatch(1),releaseSignal=new CountDownLatch(1);
        final AtomicBoolean interrupted=new AtomicBoolean(),closed=new AtomicBoolean();
        final AtomicLong clock=new AtomicLong();
        final Statement statement=mock(Statement.class);
        final Connection connection=mock(Connection.class);
        final ExecutionEngine engine;
        Fixture(boolean blockSignal,boolean failSetup,boolean retryClose,boolean statementCloseFailure) throws Exception {
            var factory=DatabaseStrategyFactory.withBuiltinStrategies();
            doAnswer(call->{querySeconds.set(call.getArgument(0));return null;}).when(statement).setQueryTimeout(anyInt());
            doAnswer(call->{network.set(call.getArgument(0));networkMillis.set(call.getArgument(1));if(failSetup)throw new SQLException("synthetic setup refusal");return null;}).when(connection).setNetworkTimeout(any(),anyInt());
            when(connection.createStatement()).thenReturn(statement);
            when(statement.execute(anyString())).thenAnswer(call->{
                executes.incrementAndGet();entered.countDown();
                try{await(releaseQuery);}catch(InterruptedException error){interrupted.set(true);throw error;}
                throw new SQLException("synthetic query cancelled","72000",1013);
            });
            doAnswer(call->{signalEntered.countDown();if(blockSignal)await(releaseSignal);return null;}).when(statement).cancel();
            if(statementCloseFailure)doThrow(new SQLException("synthetic statement close refusal")).when(statement).close();
            doAnswer(call->{if(retryClose&&closes.incrementAndGet()==1)throw new SQLException("synthetic close refusal");closed.set(true);return null;}).when(connection).close();
            when(connection.isClosed()).thenAnswer(call->closed.get());
            var service=new ConnectionService(null,null,factory,leases) {
                @Override public Optional<ConnectionConfig> getById(String id){return Optional.of(current.get());}
                @Override public Connection openOrdinaryConnection(String id,String revision,WorkloadLeaseManager.Ticket ticket)throws SQLException {
                    if(!ConnectionIdentity.revision(current.get()).equals(revision)){ticket.release();throw new SQLException("CONNECTION_CHANGED");}
                    opens.incrementAndGet();return leases.track(connection,ticket);
                }
            };
            engine=new ExecutionEngine(service,new ExplainServiceFactory(List.of()),factory,new SelectOnlyGuard(new SqlDialectResolver(factory)),30,100,clock::get);
        }
        Fixture() throws Exception {this(false,false,false,false);}
        String start(Integer timeout)throws Exception {var id=engine.execute("SELECT 1 FROM dual","synthetic",timeout);await(entered);return id;}
        public void close(){releaseQuery.countDown();releaseSignal.countDown();engine.shutdown();leases.close();}
    }
    @Test void callbacksAndBlockedCancelBothPinWorkloadAfterQueryTerminal() throws Exception {
        try(var f=new Fixture(true,false,false,false)) {
            String id=f.start(30);var callbackStarted=new CountDownLatch(1);var releaseCallback=new CountDownLatch(1);
            try {
                f.network.get().execute(()->{callbackStarted.countDown();try{await(releaseCallback);}catch(InterruptedException error){Thread.currentThread().interrupt();throw new IllegalStateException(error);}});
                await(callbackStarted);assertTrue(f.engine.cancel(id));await(f.signalEntered);
                f.releaseQuery.countDown();eventually(()->f.closed.get());
                assertEquals(ExecutionStatus.CANCELLED,f.engine.getStatus(id).status());
                assertFalse((boolean)lifecycle(f.engine,id).get("settled"));
                assertThrows(WorkloadBusyException.class,f.leases::acquireExclusive);
                f.engine.beginShutdown();assertFalse((boolean)f.engine.shutdownSnapshot().get("drained"));
                f.clock.set(TimeUnit.MINUTES.toNanos(6));f.engine.removeExpired();assertNotNull(f.engine.getStatus(id));
                assertFalse(f.interrupted.get());
            } finally {releaseCallback.countDown();f.releaseSignal.countDown();}
            eventually(()->(boolean)lifecycle(f.engine,id).get("settled"));
            eventually(()->f.leases.snapshot().ordinaryTickets()==0);
            assertTrue((boolean)f.engine.shutdownSnapshot().get("drained"));
            var exclusive=f.leases.acquireExclusive();exclusive.release();
            f.engine.removeExpired();assertNull(f.engine.getStatus(id));
        }
    }
    @Test void queryAndNetworkTimeoutsUseTheEffectivePerRequestValue() throws Exception {
        for(int seconds:new int[]{30,180})try(var f=new Fixture()) {
            String id=f.start(seconds);assertEquals(seconds,f.querySeconds.get());assertEquals(seconds*1000+15000,f.networkMillis.get());
            f.releaseQuery.countDown();eventually(()->(boolean)lifecycle(f.engine,id).get("settled"));
        }
    }
    @Test void networkSetupFailureAndOverflowExecuteNoSql() throws Exception {
        try(var f=new Fixture(false,true,false,false)) {
            String id=f.engine.execute("SELECT 1 FROM dual","synthetic");eventually(()->f.engine.getStatus(id).status().isTerminal());
            assertEquals("ORACLE_NETWORK_TIMEOUT_SETUP_FAILED",f.engine.getStatus(id).errorReason());assertEquals(0,f.executes.get());
            eventually(()->f.leases.snapshot().ordinaryTickets()==0);
        }
        try(var f=new Fixture()) {
            String id=f.engine.execute("SELECT 1 FROM dual","synthetic",Integer.MAX_VALUE);eventually(()->f.engine.getStatus(id).status().isTerminal());
            assertEquals("ORACLE_NETWORK_TIMEOUT_RANGE_UNSUPPORTED",f.engine.getStatus(id).errorReason());assertEquals(0,f.executes.get());
        }
    }
    @Test void retryClosureUnregistersNetworkOwnerButDoesNotHideStatementFailure() throws Exception {
        for(boolean statementFailure:new boolean[]{false,true})try(var f=new Fixture(false,false,true,statementFailure)) {
            String id=f.start(30);f.releaseQuery.countDown();eventually(()->f.closes.get()>=2);
            eventually(()->f.leases.snapshot().ordinaryTickets()==0);
            assertEquals(!statementFailure,lifecycle(f.engine,id).get("settled"));
            if(statementFailure)assertEquals("RESOURCE_CLEANUP_UNCONFIRMED",f.engine.getStatus(id).errorReason());
            f.engine.beginShutdown();
            var field=ExecutionEngine.class.getDeclaredField("networkTimeouts");field.setAccessible(true);
            assertTrue(((OracleNetworkTimeoutExecutor)field.get(f.engine)).snapshot().executorShutdown());
        }
    }
    @Test void queuedOwnerDriftRejectsBeforeOpeningOrExecuting() throws Exception {
        try(var f=new Fixture()) {
            var workers=new CountDownLatch(4);var release=new CountDownLatch(1);
            try {
                for(int i=0;i<4;i++)pool(f.engine).submit(()->{workers.countDown();try{await(release);}catch(InterruptedException error){Thread.currentThread().interrupt();}});
                await(workers);String id=f.engine.execute("SELECT 1 FROM dual","synthetic");f.current.set(config("oracle","two"));release.countDown();
                eventually(()->f.engine.getStatus(id).status().isTerminal());
                assertEquals("EXECUTION_OWNER_CHANGED",f.engine.getStatus(id).errorReason());assertEquals(0,f.opens.get());assertEquals(0,f.executes.get());
                eventually(()->f.leases.snapshot().ordinaryTickets()==0);
            } finally {release.countDown();}
        }
    }
    @Test void queuedCancelAndShutdownNeverStartJdbc() throws Exception {
        try(var f=new Fixture()) {
            var workers=new CountDownLatch(4);var release=new CountDownLatch(1);
            try {
                for(int i=0;i<4;i++)pool(f.engine).submit(()->{workers.countDown();try{await(release);}catch(InterruptedException error){Thread.currentThread().interrupt();}});
                await(workers);String cancelled=f.engine.execute("SELECT 1 FROM dual","synthetic"),shutdown=f.engine.execute("SELECT 2 FROM dual","synthetic");
                assertTrue(f.engine.cancel(cancelled));f.engine.beginShutdown();release.countDown();
                assertTrue((boolean)lifecycle(f.engine,cancelled).get("settled"));assertTrue((boolean)lifecycle(f.engine,shutdown).get("settled"));
                assertTrue((boolean)f.engine.shutdownSnapshot().get("drained"));assertEquals(0,f.opens.get());
                assertEquals("APPLICATION_SHUTDOWN",assertThrows(IllegalStateException.class,()->f.engine.execute("SELECT 3 FROM dual","synthetic")).getMessage());
            } finally {release.countDown();}
        }
    }
    @Test void unsupportedOracleSyntaxHasTypedFailureAndNoConnection() throws Exception {
        for(String sql:List.of("SELECT seq.NEXTVAL FROM dual","SELECT q'[text]' FROM dual","SELECT CAST(1 AS NUMBER(39)) FROM dual"))try(var f=new Fixture()) {
            var id=f.engine.execute(sql,"synthetic");eventually(()->f.engine.getStatus(id).status().isTerminal());
            assertNotNull(f.engine.getStatus(id).errorReason());assertEquals(0,f.opens.get());assertEquals(0,f.executes.get());
        }
    }
    @Test void shutdownAndConcurrentAdmissionShareOneFence() throws Exception {
        try(var f=new Fixture()) {
            ExecutorService submitters=Executors.newFixedThreadPool(8);var start=new CountDownLatch(1);var calls=new ArrayList<Callable<String>>();
            try {
                for(int i=0;i<32;i++)calls.add(()->{await(start);try{return f.engine.execute("SELECT 1 FROM dual","synthetic");}catch(IllegalStateException stopped){assertEquals("APPLICATION_SHUTDOWN",stopped.getMessage());return null;}});
                calls.add(()->{await(start);f.engine.beginShutdown();return null;});
                var pending=new ArrayList<Future<String>>();for(var call:calls)pending.add(submitters.submit(call));start.countDown();
                for(var call:pending){var id=call.get(4,TimeUnit.SECONDS);if(id!=null)assertNotNull(f.engine.getStatus(id));}
                assertThrows(IllegalStateException.class,()->f.engine.execute("SELECT 1 FROM dual","synthetic"));
            } finally {start.countDown();submitters.shutdownNow();}
        }
    }
}
