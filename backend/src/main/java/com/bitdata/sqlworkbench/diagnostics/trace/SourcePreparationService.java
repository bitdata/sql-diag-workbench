package com.bitdata.sqlworkbench.diagnostics.trace;

import com.bitdata.sqlworkbench.connection.*;
import com.bitdata.sqlworkbench.diagnostics.source.*;
import com.bitdata.sqlworkbench.execution.OracleNetworkTimeoutExecutor;
import com.bitdata.sqlworkbench.session.WorkspaceIdentity;
import com.bitdata.sqlworkbench.sql.SqlSerializationException;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/** Dedicated source tasks. No temporary graph or graph-admission bypass is involved. */
@Service
public final class SourcePreparationService {
    public enum Kind { DIRECT, COMPLEX }
    public record Submit(Kind kind, PhysicalSourcePlanService.Request request) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Progress(String preparationId, Kind kind, TraceStatus status, String errorReason,
                           TraceLifecycle lifecycle, Object plan) {}

    private final ConnectionService connections;
    private final DiagnosticAdmission admission;
    private final OracleSourcePlanService oracle;
    private final SourceObservationReceipts receipts;
    private final LongSupplier clock;
    private final Map<String,State> tasks=new ConcurrentHashMap<>();
    private final ThreadPoolExecutor workers=new ThreadPoolExecutor(2,2,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(5));
    private final ThreadPoolExecutor cancellations=new ThreadPoolExecutor(2,2,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(64),r->{var t=new Thread(r,"source-preparation-cancel");t.setDaemon(true);return t;});
    private final ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"source-preparation-budget");t.setDaemon(true);return t;});
    private final OracleNetworkTimeoutExecutor network=new OracleNetworkTimeoutExecutor(2,8);
    private volatile boolean accepting=true;

    @org.springframework.beans.factory.annotation.Autowired
    public SourcePreparationService(ConnectionService connections, DiagnosticAdmission admission,
                                    OracleSourcePlanService oracle, SourceObservationReceipts receipts) {
        this(connections,admission,oracle,receipts,System::nanoTime);
    }
    SourcePreparationService(ConnectionService connections, DiagnosticAdmission admission,
                             OracleSourcePlanService oracle, SourceObservationReceipts receipts, LongSupplier clock) {
        this.connections=connections;this.admission=admission;this.oracle=oracle;this.receipts=receipts;this.clock=clock;
        timer.scheduleAtFixedRate(this::removeExpired,60,60,TimeUnit.SECONDS);
    }

    public synchronized String submit(Submit submitted) {
        if(!accepting)throw DiagnosticAdmission.failure(503,"SOURCE_PREPARATION_SHUTTING_DOWN");
        if(submitted==null||submitted.kind()==null||submitted.request()==null)throw DiagnosticAdmission.failure(422,"SOURCE_PREPARATION_INPUT_INVALID");
        var request=submitted.request();
        admission.source(request.diagnosticContext(),request.connectionId(),request.sql(),request.source());
        var config=connections.getById(request.connectionId()).orElseThrow(()->DiagnosticAdmission.failure(404,"CONNECTION_NOT_FOUND"));
        if(!"oracle".equals(config.dialect()))throw DiagnosticAdmission.failure(422,"SOURCE_PREPARATION_DIALECT_UNSUPPORTED");
        if(request.keys()==null||request.keys().isEmpty()||request.keys().size()>16||request.keys().stream().anyMatch(k->k==null||k.type()==null))throw DiagnosticAdmission.failure(422,"TYPED_KEY_REQUIRED");
        removeExpired();
        if(tasks.size()>=256)throw DiagnosticAdmission.failure(429,"SOURCE_PREPARATION_CAPACITY_EXHAUSTED");
        var state=new State(UUID.randomUUID().toString(),submitted,admission.connectionVersion(request.connectionId()),ConnectionIdentity.revision(config),clock.getAsLong());
        tasks.put(state.id,state);
        state.deadline=timer.schedule(()->stop(state,TraceStatus.FAILED,"SOURCE_PREPARATION_BUDGET_EXCEEDED"),5,TimeUnit.SECONDS);
        try { workers.execute(new Job(state)); }
        catch(RejectedExecutionException rejected) {stop(state,TraceStatus.FAILED,"SOURCE_PREPARATION_QUEUE_FULL");}
        return state.id;
    }

    private State authorized(String id) {
        State state=tasks.get(id);
        if(state==null)throw DiagnosticAdmission.failure(404,"SOURCE_PREPARATION_NOT_FOUND");
        revalidate(state);return state;
    }
    private void revalidate(State state) {
        var request=state.submitted.request();
        admission.revalidate(request.diagnosticContext(),request.connectionId(),state.version);
    }
    public Progress get(String id) {
        var state=authorized(id);expire(state);
        synchronized(state){return new Progress(id,state.submitted.kind(),state.status,state.errorReason,lifecycle(state),state.status==TraceStatus.COMPLETED?state.plan:null);}
    }
    public boolean cancel(String id) {return stop(authorized(id),TraceStatus.CANCELLED,"SOURCE_PREPARATION_CANCELLED");}
    private void expire(State state) {
        if(clock.getAsLong()-state.started>=TimeUnit.SECONDS.toNanos(5))stop(state,TraceStatus.FAILED,"SOURCE_PREPARATION_BUDGET_EXCEEDED");
    }
    private int remaining(State state) {
        expire(state);
        synchronized(state){if(state.status.isTerminal())throw DiagnosticAdmission.failure(409,state.errorReason==null?"SOURCE_PREPARATION_TERMINAL":state.errorReason);}
        return (int)Math.max(1,Math.min(5000,(TimeUnit.SECONDS.toNanos(5)-(clock.getAsLong()-state.started)+999_999)/1_000_000));
    }

    private final class Job implements Runnable {
        final State state;
        Job(State state){this.state=state;}
        @Override public void run() {
            WorkloadLeaseManager.Ticket worker=null;
            try {
                revalidate(state);remaining(state);
                worker=connections.acquireOrdinaryWorkload();
                synchronized(state) {
                    remaining(state);
                    state.worker=worker;state.resources=new TaskResources(worker,network);state.status=TraceStatus.RUNNING;
                }
                int openMillis=remaining(state);
                var connectionOwner=worker.retain();state.resources.bindConnectionTicket(connectionOwner);
                try(Connection connection=state.resources.wrap(connections.openOrdinaryWithTimeout(state.submitted.request().connectionId(),state.connectionRevision,openMillis,connectionOwner))) {
                    PreparationJdbc jdbc=new PreparationJdbc() {
                        public void checkpoint(){revalidate(state);remaining(state);}
                        public void configure(Statement statement)throws SQLException {
                            checkpoint();connection.setNetworkTimeout(Runnable::run,remaining(state));
                            statement.setQueryTimeout(Math.max(1,(remaining(state)+999)/1000));
                            synchronized(state){remaining(state);state.active=statement;}
                        }
                        public void clearStatement(){synchronized(state){state.active=null;}}
                    };
                    jdbc.checkpoint();connection.setReadOnly(true);
                    var plan=oracle.inspect(state.submitted.request(),state.submitted.kind()==Kind.DIRECT,connection,jdbc);
                    synchronized(state) {
                        jdbc.checkpoint();
                        state.plan=certify(state,plan);
                        state.status=TraceStatus.COMPLETED;
                        state.deadline.cancel(false);
                    }
                }
            } catch(WorkspaceIdentity.Failure failure){fail(state,failure.code);}
              catch(CredentialFailure failure){fail(state,failure.code);}
              catch(WorkloadBusyException failure){fail(state,failure.errorReason());}
              catch(ConnectTimeoutException failure){fail(state,"SOURCE_CONNECTION_TIMEOUT");}
              catch(SQLTimeoutException failure){fail(state,"SOURCE_METADATA_TIMEOUT");}
              catch(SQLException failure){fail(state,failure.getSQLState()!=null&&failure.getSQLState().startsWith("08")?"SOURCE_CONNECTION_FAILED":"SOURCE_METADATA_FAILED");}
              catch(SqlSerializationException failure){fail(state,failure.reason());}
              catch(Exception failure){fail(state,failure.getMessage()!=null&&failure.getMessage().matches("[A-Z][A-Z0-9_]+")?failure.getMessage():"SOURCE_PREPARATION_FAILED");}
            finally {
                synchronized(state) {
                    state.active=null;state.worker=null;
                    if(state.resources!=null)state.resources.workerReturned();
                    else state.neverStarted=true;
                }
                if(worker!=null)worker.release();
            }
        }
    }

    private Object certify(State state, ComplexSourcePlanService.Plan plan) {
        var request=state.submitted.request();
        if(state.submitted.kind()==Kind.DIRECT) {
            if(!plan.verified()||plan.sources().size()!=1)return new PhysicalSourcePlanService.Plan("UNKNOWN",false,plan.errorReason()==null?"DIRECT_PHYSICAL_SOURCE_REQUIRED":plan.errorReason(),request.connectionId(),request.sql(),plan.metadataSql(),null,null,null,List.of(),plan.observedAt());
            var source=plan.sources().get(0);
            String receipt=receipts.issue(request,source.sourceSql(),source.keys(),state.version);
            return new PhysicalSourcePlanService.Plan("COMPLETED",true,null,request.connectionId(),request.sql(),plan.metadataSql(),source.schema(),source.relation(),source.sourceSql(),source.keys(),plan.observedAt(),receipt);
        }
        List<ComplexSourcePlanService.Source> sources=new ArrayList<>();
        for(var source:plan.sources()) {
            remaining(state);
            String receipt=receipts.issue(request,source.sourceSql(),source.keys(),state.version);
            sources.add(new ComplexSourcePlanService.Source(source.occurrence(),source.schema(),source.relation(),source.path(),source.sourceSql(),source.keys(),source.mappings(),receipt));
        }
        return new ComplexSourcePlanService.Plan(plan.status(),plan.verified(),plan.errorReason(),plan.connectionId(),plan.originalSql(),plan.metadataSql(),List.copyOf(sources),plan.issues(),plan.observedAt());
    }

    private void fail(State state,String reason) {
        synchronized(state) {
            if(!state.status.isTerminal()) {
                state.errorReason=reason;state.status=TraceStatus.FAILED;
                if(state.deadline!=null)state.deadline.cancel(false);
            } else if(state.resources!=null&&state.status==TraceStatus.COMPLETED) {
                // A close failure after publication remains visible in resourceObservation.
                state.cleanupError=reason;
            }
        }
    }
    private boolean stop(State state,TraceStatus terminal,String reason) {
        Signal signal=null;
        synchronized(state) {
            if(state.status.isTerminal())return false;
            state.neverStarted=state.resources==null;
            state.errorReason=reason;state.status=terminal;
            if(state.deadline!=null)state.deadline.cancel(false);
            if(state.active==null)state.cancelSignal="NOT_REQUIRED";
            else {
                state.cancelSignal="PENDING";
                signal=new Signal(state,state.active,state.worker.retain());
            }
        }
        if(signal!=null)try{cancellations.execute(signal);}catch(RejectedExecutionException rejected){signal.discard("CANCEL_SIGNAL_REJECTED");}
        return true;
    }
    private static final class Signal implements Runnable {
        final State state;final Statement statement;final WorkloadLeaseManager.Ticket owner;final AtomicBoolean claimed=new AtomicBoolean();
        Signal(State state,Statement statement,WorkloadLeaseManager.Ticket owner){this.state=state;this.statement=statement;this.owner=owner;}
        public void run(){if(!claimed.compareAndSet(false,true))return;try{statement.cancel();synchronized(state){state.cancelSignal="SENT";}}catch(Exception failure){synchronized(state){state.cancelSignal="FAILED";state.cancelReason="CANCEL_SIGNAL_FAILED";}}finally{owner.release();}}
        void discard(String reason){if(!claimed.compareAndSet(false,true))return;try{synchronized(state){state.cancelSignal="REJECTED";state.cancelReason=reason;}}finally{owner.release();}}
    }
    private Map<String,Object> observation(State state) {
        return state.resources!=null?state.resources.snapshot(state.status.isTerminal()):state.neverStarted?
            Map.of("state","CONFIRMED","confirmationBasis","NEVER_STARTED","errorReason",""):
            Map.of("state","UNKNOWN","errorReason","TASK_OWNER_NOT_REGISTERED");
    }
    private TraceLifecycle lifecycle(State state) {
        var raw=observation(state);String observed=String.valueOf(raw.get("state"));
        String cleanup=switch(observed){case "CONFIRMED"->state.resources==null?"NO_RESOURCES":"CLOSED";case "PENDING"->"PENDING";default->state.status==TraceStatus.QUEUED?"NOT_STARTED":state.status.isTerminal()?"UNCONFIRMED":"PENDING";};
        boolean settled=state.status.isTerminal()&&Set.of("CLOSED","NO_RESOURCES").contains(cleanup)&&!state.cancelSignal.equals("PENDING");
        String reason=Objects.toString(raw.get("errorReason"),"");
        if(reason.isEmpty())reason=state.cancelReason!=null?state.cancelReason:state.cleanupError;
        if(reason!=null&&reason.isEmpty())reason=null;
        boolean degraded=Boolean.TRUE.equals(raw.get("degraded"))||cleanup.equals("UNCONFIRMED")||state.cancelReason!=null||state.cleanupError!=null;
        var signature=Arrays.asList(state.status,state.errorReason,state.cancelSignal,cleanup,reason,raw);
        if(!signature.equals(state.signature)){state.revision++;state.signature=signature;}
        return new TraceLifecycle(1,state.revision,settled,state.cancelSignal,cleanup,reason,degraded,raw,"UNKNOWN","UNCONFIRMED");
    }
    private synchronized void removeExpired() {
        long now=clock.getAsLong();
        tasks.values().removeIf(state->{synchronized(state){
            boolean settled=lifecycle(state).settled();
            if(!settled){state.confirmed=Long.MIN_VALUE;return false;}
            if(state.confirmed==Long.MIN_VALUE){state.confirmed=now;return false;}
            return now-state.confirmed>=TimeUnit.MINUTES.toNanos(5);
        }});
    }
    @PreDestroy public synchronized void shutdown() {
        accepting=false;workers.shutdown();
        for(var state:tasks.values())stop(state,TraceStatus.FAILED,state.status==TraceStatus.QUEUED?"SOURCE_PREPARATION_SHUTDOWN_BEFORE_START":"SOURCE_PREPARATION_SHUTDOWN_REQUESTED");
        var discarded=new ArrayList<Runnable>();workers.getQueue().drainTo(discarded);
        timer.shutdownNow();cancellations.shutdown();
        discarded.clear();cancellations.getQueue().drainTo(discarded);
        for(var abandoned:discarded)if(abandoned instanceof Signal signal)signal.discard("CANCEL_SIGNAL_NOT_SENT_SHUTDOWN");
        network.beginShutdown();
    }
    private static final class State {
        final String id,version,connectionRevision;final Submit submitted;final long started;
        TraceStatus status=TraceStatus.QUEUED;String errorReason,cancelReason,cleanupError;String cancelSignal="NOT_REQUESTED";
        boolean neverStarted;Statement active;WorkloadLeaseManager.Ticket worker;TaskResources resources;Object plan;
        ScheduledFuture<?> deadline;long confirmed=Long.MIN_VALUE,revision;List<Object> signature;
        State(String id,Submit submitted,String version,String connectionRevision,long started){this.id=id;this.submitted=submitted;this.version=version;this.connectionRevision=connectionRevision;this.started=started;}
    }
}
