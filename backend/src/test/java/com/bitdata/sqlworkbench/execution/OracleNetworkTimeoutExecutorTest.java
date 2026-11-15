package com.bitdata.sqlworkbench.execution;

import com.bitdata.sqlworkbench.connection.WorkloadLeaseManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.concurrent.*;

class OracleNetworkTimeoutExecutorTest {
    static void require(boolean value,String label){if(!value)throw new AssertionError(label);}
    static void waitFor(java.util.function.BooleanSupplier condition)throws Exception {long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(!condition.getAsBoolean()&&System.nanoTime()<end)Thread.sleep(5);require(condition.getAsBoolean(),"bounded observation");}
    @org.junit.jupiter.api.Test
    void retainsRealLeaseOwnershipAcrossAllCallbackOutcomes()throws Exception {
        String[] args={java.nio.file.Files.createTempFile("oracle-network-callback", ".json").toString()};
        var results=new ArrayList<Object>();
        try(var manager=new LeaseScope()) {
            var owner=new OracleNetworkTimeoutExecutor(1,1);var reasons=new CopyOnWriteArrayList<String>();var ticket=manager.value.acquireOrdinary();var registration=owner.register(ticket,reasons::add);
            var cancelGate=new CountDownLatch(1);var cancelStarted=new CountDownLatch(1);var cancellation=Executors.newSingleThreadExecutor(r->{var t=new Thread(r);t.setDaemon(true);return t;});
            cancellation.execute(()->{cancelStarted.countDown();try{cancelGate.await();}catch(InterruptedException ignored){Thread.currentThread().interrupt();}});
            require(cancelStarted.await(2,TimeUnit.SECONDS),"cancellation blocked");
            var callback=new CountDownLatch(1);registration.execute(callback::countDown);require(callback.await(2,TimeUnit.SECONDS),"independent callback not blocked by cancellation");
            owner.beginShutdown();require(!owner.snapshot().executorShutdown(),"still-open connection retains network executor");
            callback=new CountDownLatch(1);registration.execute(callback::countDown);require(callback.await(2,TimeUnit.SECONDS),"callback after drain start");waitFor(()->owner.snapshot().pendingCallbacks()==0);
            registration.connectionCloseConfirmed();ticket.release();waitFor(()->owner.snapshot().executorShutdown());require(manager.value.snapshot().ordinaryTickets()==0,"isolation no leak");
            cancelGate.countDown();cancellation.shutdown();results.add(Map.of("case","cancel-pool-isolation-and-drain-lifetime","passed",true,"snapshot",owner.snapshot()));
        }
        try(var manager=new LeaseScope()) {
            var owner=new OracleNetworkTimeoutExecutor(1,1);var reasons=new CopyOnWriteArrayList<String>();var ticket=manager.value.acquireOrdinary();var registration=owner.register(ticket,reasons::add);
            var gate=new CountDownLatch(1);var started=new CountDownLatch(1);registration.execute(()->{started.countDown();try{gate.await();}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}});require(started.await(2,TimeUnit.SECONDS),"callback blocked");
            registration.execute(()->{});boolean rejected=false;try{registration.execute(()->{throw new AssertionError("rejected callback ran");});}catch(RejectedExecutionException expected){rejected=true;}
            require(rejected&&reasons.contains("NETWORK_TIMEOUT_CALLBACK_REJECTED"),"saturation observable");
            require(registration.pendingCallbacks()==2,"per-execution callback ownership");
            owner.beginShutdown();registration.connectionCloseConfirmed();ticket.release();require(manager.value.snapshot().ordinaryTickets()==1,"pending callbacks retain real workload ticket");require(!owner.snapshot().executorShutdown(),"do not shutdown pending callback");
            gate.countDown();waitFor(()->owner.snapshot().executorShutdown());require(manager.value.snapshot().ordinaryTickets()==0,"callbacks release after completion");
            results.add(Map.of("case","bounded-saturation-retains-ownership","passed",true,"snapshot",owner.snapshot(),"reasons",reasons));
        }
        try(var manager=new LeaseScope()) {
            var owner=new OracleNetworkTimeoutExecutor(1,1);var reasons=new CopyOnWriteArrayList<String>();var ticket=manager.value.acquireOrdinary();var registration=owner.register(ticket,reasons::add);
            registration.execute(()->{throw new IllegalStateException("synthetic callback failure");});waitFor(()->owner.snapshot().pendingCallbacks()==0);require(reasons.contains("NETWORK_TIMEOUT_CALLBACK_FAILED"),"failure reason visible");
            owner.beginShutdown();registration.connectionCloseConfirmed();ticket.release();require(owner.snapshot().executorShutdown()&&manager.value.snapshot().ordinaryTickets()==0,"failure releases reference");
            boolean rejected=false;try{registration.execute(()->{});}catch(RejectedExecutionException expected){rejected=true;}require(rejected&&reasons.contains("NETWORK_TIMEOUT_CALLBACK_AFTER_CLOSE"),"late callback rejected observably");
            results.add(Map.of("case","callback-failure-and-after-close","passed",true,"snapshot",owner.snapshot(),"reasons",reasons));
        }
        try(var manager=new LeaseScope()) {
            var owner=new OracleNetworkTimeoutExecutor(1,1);owner.beginShutdown();boolean refused=false;var ticket=manager.value.acquireOrdinary();try{owner.register(ticket,r->{});}catch(IllegalStateException expected){refused=true;}finally{ticket.release();}
            require(refused&&owner.snapshot().executorShutdown()&&manager.value.snapshot().ordinaryTickets()==0,"register after stop refused");results.add(Map.of("case","new-registration-after-stop","passed",true));
        }
        try(var manager=new LeaseScope()) {
            var owner=new OracleNetworkTimeoutExecutor(1,1);var reasons=new CopyOnWriteArrayList<String>();var ticket=manager.value.acquireOrdinary();var registration=owner.register(ticket,reasons::add);ticket.release();
            boolean rejected=false;try{registration.execute(()->{});}catch(RejectedExecutionException expected){rejected=true;}
            require(rejected&&reasons.contains("NETWORK_TIMEOUT_CALLBACK_OWNER_UNAVAILABLE")&&registration.pendingCallbacks()==0,"lost owner is observable without new references");
            owner.beginShutdown();registration.connectionCloseConfirmed();require(owner.snapshot().executorShutdown(),"owner-lost cleanup");results.add(Map.of("case","owner-unavailable-refusal","passed",true,"reasons",reasons));
        }
        org.junit.jupiter.api.Assertions.assertEquals(5, results.size());
        java.nio.file.Files.deleteIfExists(java.nio.file.Path.of(args[0]));
    }
    static final class LeaseScope implements AutoCloseable {final WorkloadLeaseManager value=new WorkloadLeaseManager();public void close(){value.close();}}
}
