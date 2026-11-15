package com.bitdata.sqlworkbench.execution;

import com.bitdata.sqlworkbench.connection.WorkloadLeaseManager;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Owns bounded JDBC network callbacks independently of statement cancellation. */
public final class OracleNetworkTimeoutExecutor {
    private final ThreadPoolExecutor callbacks;
    private boolean stopping;
    private int registrations, pending, rejected, failed;
    public OracleNetworkTimeoutExecutor(int workers,int capacity) {
        callbacks=new ThreadPoolExecutor(workers,workers,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(capacity),
            r->{var t=new Thread(r,"oracle-network-timeout");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    }
    public synchronized Registration register(WorkloadLeaseManager.Ticket ticket,Consumer<String> reason) {
        if(stopping)throw new IllegalStateException("APPLICATION_SHUTDOWN");
        registrations++;return new Registration(ticket,reason);
    }
    public synchronized void beginShutdown() {stopping=true;maybeStop();}
    private void maybeStop() {if(stopping&&registrations==0&&pending==0)callbacks.shutdown();}
    public synchronized Snapshot snapshot() {return new Snapshot(stopping,registrations,pending,rejected,failed,callbacks.isShutdown());}
    public record Snapshot(boolean stopping,int openRegistrations,int pendingCallbacks,int rejectedCallbacks,int failedCallbacks,boolean executorShutdown) {}
    public final class Registration implements Executor {
        private final WorkloadLeaseManager.Ticket ticket;
        private final Consumer<String> reason;
        private boolean closed;
        private int inFlight;
        private Registration(WorkloadLeaseManager.Ticket ticket,Consumer<String> reason) {this.ticket=ticket;this.reason=reason;}
        @Override public void execute(Runnable command) {
            synchronized(OracleNetworkTimeoutExecutor.this) {
                if(closed) {rejected++;reason.accept("NETWORK_TIMEOUT_CALLBACK_AFTER_CLOSE");throw new RejectedExecutionException("NETWORK_TIMEOUT_CALLBACK_AFTER_CLOSE");}
                WorkloadLeaseManager.Ticket retained;
                try {retained=ticket.retain();}
                catch(IllegalStateException unavailable) {rejected++;reason.accept("NETWORK_TIMEOUT_CALLBACK_OWNER_UNAVAILABLE");throw new RejectedExecutionException("NETWORK_TIMEOUT_CALLBACK_OWNER_UNAVAILABLE",unavailable);}
                pending++;inFlight++;
                try {callbacks.execute(()->{
                    try {command.run();}
                    catch(RuntimeException failure) {synchronized(OracleNetworkTimeoutExecutor.this){failed++;reason.accept("NETWORK_TIMEOUT_CALLBACK_FAILED");}}
                    finally {retained.release();synchronized(OracleNetworkTimeoutExecutor.this){pending--;inFlight--;maybeStop();}}
                });}
                catch(RejectedExecutionException unavailable) {
                    pending--;inFlight--;retained.release();rejected++;reason.accept("NETWORK_TIMEOUT_CALLBACK_REJECTED");maybeStop();throw unavailable;
                }
            }
        }
        /** Only call after physical connection closure is confirmed, never just on query terminal. */
        public void connectionCloseConfirmed() {synchronized(OracleNetworkTimeoutExecutor.this){if(closed)return;closed=true;registrations--;maybeStop();}}
        public int pendingCallbacks() {synchronized(OracleNetworkTimeoutExecutor.this){return inFlight;}}
    }
}
