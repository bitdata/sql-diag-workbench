package com.bitdata.sqlworkbench.execution;

import com.bitdata.sqlworkbench.connection.ConnectionService;
import com.bitdata.sqlworkbench.connection.DatabaseStrategy;
import com.bitdata.sqlworkbench.connection.DatabaseStrategyFactory;
import com.bitdata.sqlworkbench.execution.model.ExecutionResult;
import com.bitdata.sqlworkbench.execution.model.PlanCapture;
import com.bitdata.sqlworkbench.execution.model.PlanCapabilities;
import com.bitdata.sqlworkbench.execution.model.ExecutionStatus;
import com.bitdata.sqlworkbench.execution.model.ResultCompleteness;
import com.bitdata.sqlworkbench.sql.SelectOnlyGuard;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.sql.*;
import java.util.*;
import java.util.concurrent.*;

@Component
public class ExecutionEngine {

    private static final Logger log = LoggerFactory.getLogger(ExecutionEngine.class);
    private static final int POOL_SIZE = 4;

    /** Hard upper bound on maxRows to prevent memory exhaustion from
     *  accidental or malicious large values. */
    static final int MAX_ROWS_UPPER_BOUND = 10_000;

    /** Per-statement query timeout (seconds). Configurable via
     *  {@code sql-workbench.execution.query-timeout-seconds}. */
    private final int queryTimeoutSeconds;

    /** Default row limit applied when a request does not specify maxRows.
     *  Configurable via {@code sql-workbench.execution.max-rows}. */
    private final int defaultMaxRows;

    private final ConnectionService connectionService;
    private final ExplainServiceFactory explainServiceFactory;
    private final DatabaseStrategyFactory strategyFactory;
    private final SelectOnlyGuard selectOnlyGuard;
    private final ThreadPoolExecutor executor;
    private final Map<String, ExecutionContext> executions = new ConcurrentHashMap<>();
    private final Object admissionLock = new Object();
    private volatile boolean stopping;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private PlanCapabilityService planCapabilityService;
    private final OracleNetworkTimeoutExecutor networkTimeouts = new OracleNetworkTimeoutExecutor(2, 8);
    private final ThreadPoolExecutor exitCancellation=new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(8),r->{var t=new Thread(r,"execution-shutdown-cancel");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    private final ThreadPoolExecutor ordinaryCancellation = new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(8),r->{var t=new Thread(r,"oracle-statement-cancel");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());

    /** Internal lifecycle projection for ordinary Oracle execution. */
    public Map<String,Object> ordinaryLifecycle(String id) {
        var ctx=executions.get(id);if(ctx==null)return Map.of("available",false);
        synchronized(ctx){reconcileOrdinary(ctx);return Map.of("available",true,"oracleSnapshot",ctx.ordinaryOracle,"cancelSignal",ctx.ordinarySignal,
            "cleanup",ctx.ordinaryCleanup,"workerFinished",ctx.workerFinished,"cancelWorkers",ctx.cancelWorkers,
            "started",ctx.ordinaryStarted,"ownerRevision",ctx.ordinaryRevision==null?"NONE":ctx.ordinaryRevision);}
    }
    /** Versioned HTTP lifecycle projection. Revision advances on changes observed by this endpoint, not on every internal event. */
    public Object ordinaryResponse(String id) {
        var ctx=executions.get(id);if(ctx==null)return null;
        if(!ctx.ordinaryOracle) {
            reconcileNativeNetwork(ctx);
            var result=getStatus(id);if(!ctx.nativePlan)return result;
            synchronized(ctx) {
                java.util.Map<String,Object> resources=ctx.nativeTicket==null?java.util.Map.of("references",0,"entryReleased",true,"unconfirmed",false,"reasons",java.util.List.of()):ctx.nativeTicket.resourceSnapshot();
                boolean settled=result.status().isTerminal()&&nativeOwnersSettled(ctx);
                String reason=ctx.networkReason.get()!=null?ctx.networkReason.get():ctx.cleanupReason;
                if(reason==null&&"FAILED".equals(ctx.nativeSignal))reason="CANCEL_SIGNAL_FAILED";
                if(reason==null&&"REJECTED".equals(ctx.nativeSignal))reason="CANCEL_SIGNAL_UNAVAILABLE";
                String cleanup=settled?(ctx.nativeTicket==null?"NO_RESOURCES":"CLOSED"):reason!=null&&!java.util.Set.of("CLEANUP_PENDING","CANCEL_SIGNAL_PENDING").contains(reason)?"UNCONFIRMED":"PENDING";
                var signature=new java.util.ArrayList<Object>();signature.add(result.status());signature.add(ctx.nativeSignal);signature.add(cleanup);signature.add(settled);signature.add(reason);signature.add(resources);
                if(!signature.equals(ctx.nativeObservedSignature)){ctx.nativeObservedSignature=signature;ctx.nativeObservedRevision++;}
                var observation=new com.bitdata.sqlworkbench.diagnostics.trace.TraceLifecycle(1,ctx.nativeObservedRevision,settled,ctx.nativeSignal,cleanup,reason,!settled,resources,"UNKNOWN","UNCONFIRMED");
                var mapper=new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
                var body=mapper.convertValue(result,new com.fasterxml.jackson.core.type.TypeReference<java.util.LinkedHashMap<String,Object>>(){});body.put("lifecycle",observation);return body;
            }
        }
        synchronized(ctx) {
            var result=getStatus(id);var lc=ordinaryLifecycle(id);
            String signal=(String)lc.get("cancelSignal"),clean=(String)lc.get("cleanup");
            boolean settled=result.status().isTerminal()&&ctx.workerFinished&&ctx.cancelWorkers==0 && pendingNetworkCallbacks(ctx)==0
                &&("CLOSED".equals(clean)||"NO_RESOURCES".equals(clean));
            String reason=result.errorReason();
            String signature=result.status()+":"+signal+":"+clean+":"+settled+":"+reason;
            if(!signature.equals(ctx.ordinaryObservedSignature)){ctx.ordinaryObservedSignature=signature;ctx.ordinaryObservedRevision++;}
            var observation=new java.util.LinkedHashMap<String,Object>();
            observation.put("version",1);observation.put("revision",ctx.ordinaryObservedRevision);
            observation.put("settled",settled);observation.put("cancelSignal",signal);observation.put("cleanup",clean);observation.put("errorReason",reason);
            var mapper=new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
            var body=mapper.convertValue(result,new com.fasterxml.jackson.core.type.TypeReference<java.util.LinkedHashMap<String,Object>>(){});
            body.put("lifecycle",observation);return body;
        }
    }
    private boolean cancelOrdinaryOracle(ExecutionContext ctx,String reason) {
        Statement statement;
        com.bitdata.sqlworkbench.connection.WorkloadLeaseManager.Ticket signalTicket;
        synchronized(ctx) {
            if(ctx.published.status()!=null&&ctx.published.status().isTerminal())return false;
            ctx.cancelled=true;
            if(!ctx.publish(ExecutionStatus.CANCELLED,errorResult(ctx.executionId,ExecutionStatus.CANCELLED,reason)))return false;
            statement=ctx.statement;
            if(ctx.future!=null)ctx.future.cancel(false);
            if(!ctx.ordinaryStarted){ctx.workerFinished=true;ctx.ordinaryCleanup="NO_RESOURCES";}
            if(statement==null){ctx.ordinarySignal="NOT_REQUIRED";return true;}
            signalTicket=ctx.ordinaryWorkerTicket.retain();
            ctx.ordinarySignal="PENDING";ctx.cancelWorkers++;
        }
        try {ordinaryCancellation.execute(()->{
            try{statement.cancel();ctx.ordinarySignal="SENT";}
            catch(Exception failure){ctx.ordinarySignal="FAILED";ctx.ordinaryReason="CANCEL_SIGNAL_FAILED";}
            finally{signalTicket.release();synchronized(ctx){ctx.cancelWorkers--;}}
        });} catch(RejectedExecutionException failure) {
            signalTicket.release();synchronized(ctx){ctx.cancelWorkers--;ctx.ordinarySignal="REJECTED";ctx.ordinaryReason="CANCEL_SIGNAL_REJECTED";}
        }
        return true;
    }
    private static int pendingNetworkCallbacks(ExecutionContext ctx) {
        return ctx.networkRegistration == null ? 0 : ctx.networkRegistration.pendingCallbacks();
    }
    private void reconcileOrdinary(ExecutionContext ctx) {
        var ticket=ctx.ordinaryConnectionTicket;
        if (ctx.ordinaryOracle && ticket != null && ticket.isReleased() && !ticket.isUnconfirmed()) {
            ctx.exitConnectionClosed=true;
            if(ctx.networkRegistration!=null)ctx.networkRegistration.connectionCloseConfirmed();
        }
        if(ctx.ordinaryOracle&&ctx.workerFinished&&!ctx.ordinaryStatementCloseFailed&&ticket!=null&&ticket.isReleased()&&!ticket.isUnconfirmed()
            &&"UNCONFIRMED".equals(ctx.ordinaryCleanup)) {
            ctx.ordinaryCleanup="CLOSED";ctx.exitConnectionClosed=true;
            if(ctx.networkRegistration!=null)ctx.networkRegistration.connectionCloseConfirmed();
            if("RESOURCE_CLEANUP_UNCONFIRMED".equals(ctx.ordinaryReason))ctx.ordinaryReason=null;
        }
    }
    private Connection trackOrdinary(Connection connection,ExecutionContext ctx) {
        ctx.exitConnectionOpened=true;
        return (Connection)java.lang.reflect.Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
            try{
                Object value=method.invoke(connection,args);
                if(method.getName().equals("close")) {
                    ctx.exitConnectionClosed=true;
                    if(ctx.networkRegistration!=null)ctx.networkRegistration.connectionCloseConfirmed();
                }
                if(method.getName().equals("createStatement")&&value instanceof Statement statement)
                    return java.lang.reflect.Proxy.newProxyInstance(Statement.class.getClassLoader(),new Class<?>[]{Statement.class},(sp,sm,sa)->{
                        try{return sm.invoke(statement,sa);}catch(java.lang.reflect.InvocationTargetException error){
                            if(sm.getName().equals("close")){ctx.ordinaryStatementCloseFailed=true;ctx.ordinaryCleanup="UNCONFIRMED";ctx.ordinaryReason="RESOURCE_CLEANUP_UNCONFIRMED";}
                            throw error.getCause();
                        }
                    });
                return value;
            }catch(java.lang.reflect.InvocationTargetException error){
                if(method.getName().equals("close")){ctx.ordinaryCleanup="UNCONFIRMED";ctx.ordinaryReason="RESOURCE_CLEANUP_UNCONFIRMED";}
                throw error.getCause();
            }
        });
    }
    private Connection openOrdinary(ExecutionContext ctx)throws SQLException {
        if(!ctx.ordinaryOracle)return trackOrdinary(connectionService.openJdbcConnection(ctx.connectionId),ctx);
        if(ctx.cancelled)throw new SQLException("CANCELLED_BEFORE_CONNECT");
        ctx.ordinaryWorkerTicket=connectionService.acquireOrdinaryWorkload();
        ctx.ordinaryConnectionTicket=ctx.ordinaryWorkerTicket.retain();
        return trackOrdinary(connectionService.openOrdinaryConnection(ctx.connectionId,ctx.ordinaryRevision,ctx.ordinaryConnectionTicket),ctx);
    }
    private final java.util.function.LongSupplier ticker;
    private static final long RETENTION_NANOS = TimeUnit.MINUTES.toNanos(5);
    private static final int CAPACITY = 256;
    private final ScheduledExecutorService cleanup = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "execution-retention"); thread.setDaemon(true); return thread;
    });
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private com.bitdata.sqlworkbench.history.HistoryStore historyStore;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private PlanObservationReceiptStore observationReceiptStore;

    @org.springframework.beans.factory.annotation.Autowired
    public ExecutionEngine(
        ConnectionService connectionService,
        ExplainServiceFactory explainServiceFactory,
        DatabaseStrategyFactory strategyFactory,
        SelectOnlyGuard selectOnlyGuard,
        @Value("${sql-workbench.execution.query-timeout-seconds:30}") int queryTimeoutSeconds,
        @Value("${sql-workbench.execution.max-rows:100}") int defaultMaxRows
    ) {
        this(connectionService, explainServiceFactory, strategyFactory, selectOnlyGuard, queryTimeoutSeconds, defaultMaxRows, System::nanoTime);
    }

    ExecutionEngine(ConnectionService connectionService, ExplainServiceFactory explainServiceFactory,
                    DatabaseStrategyFactory strategyFactory, SelectOnlyGuard selectOnlyGuard,
                    int queryTimeoutSeconds, int defaultMaxRows, java.util.function.LongSupplier ticker) {
        this.connectionService = connectionService;
        this.explainServiceFactory = explainServiceFactory;
        this.strategyFactory = strategyFactory;
        this.selectOnlyGuard = selectOnlyGuard;
        this.queryTimeoutSeconds = queryTimeoutSeconds;
        if (defaultMaxRows < 1 || defaultMaxRows > MAX_ROWS_UPPER_BOUND)
            throw new IllegalArgumentException("execution.max-rows must be between 1 and 10000");
        this.defaultMaxRows = defaultMaxRows;
        this.ticker = ticker;
        this.executor = new ThreadPoolExecutor(
            POOL_SIZE, POOL_SIZE,
            60, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(10),
            new ThreadPoolExecutor.AbortPolicy()
        );
        cleanup.scheduleAtFixedRate(this::removeExpired, 1, 1, TimeUnit.MINUTES);
    }

    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private com.bitdata.sqlworkbench.session.SessionStore sessionStore;
    private final ScheduledExecutorService planDeadlines = Executors.newSingleThreadScheduledExecutor(r -> {
        var t=new Thread(r,"native-plan-deadline");t.setDaemon(true);return t;
    });
    private final ThreadPoolExecutor planCancellation = new ThreadPoolExecutor(4,4,0,TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(32),r->{var t=new Thread(r,"native-plan-cancel");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    private final ThreadPoolExecutor planCleanup = new ThreadPoolExecutor(4,4,0,TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(32),r->{var t=new Thread(r,"native-plan-cleanup");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    private final ThreadPoolExecutor planMetadata = new ThreadPoolExecutor(4,4,0,TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(16),r->{var t=new Thread(r,"native-plan-metadata");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    // Package-internal test injection exercises the same timers without waiting two minutes.
    private long nativeBudgetMillis=120_000;
    private long metadataBudgetMillis=5_000;

    // ─── Public API ────────────────────────────────────────────────────────
    //
    // Two distinct paths so the UI's Data/Performance modes map cleanly:
    //   execute()  — SELECT only, returns rows, no EXPLAIN.
    //   analyze()  — EXPLAIN (ANALYZE true, FORMAT JSON), returns plan, no rows.
    // They never share a JDBC connection, so a SELECT's query-timeout cancel
    // can never bleed into EXPLAIN.

    /** Submit a SELECT for execution. Returns an id; poll {@link #getStatus}. */
    public String execute(String sql, String connectionId) {
        return execute(sql, connectionId, null);
    }

    /** Submit a SELECT with an optional per-request timeout override (seconds). */
    public String execute(String sql, String connectionId, Integer queryTimeoutSeconds) {
        return execute(sql, connectionId, queryTimeoutSeconds, null);
    }

    /** Submit a SELECT with per-request timeout and maxRows overrides. */
    public String execute(String sql, String connectionId, Integer queryTimeoutSeconds, Integer maxRows) {
        return submit(sql, connectionId, Kind.EXECUTE, queryTimeoutSeconds, maxRows, null);
    }
    public String execute(String sql, String connectionId, Integer timeout, Integer maxRows, String sessionId) {
        return submit(sql,connectionId,Kind.EXECUTE,timeout,maxRows,null,sessionId);
    }

    /** Submit an EXPLAIN for execution. Returns an id; poll {@link #getStatus}. */
    public String analyze(String sql, String connectionId) {
        return analyze(sql, connectionId, null);
    }

    /** Submit an EXPLAIN with an optional per-request timeout override (seconds). */
    public String analyze(String sql, String connectionId, Integer queryTimeoutSeconds) {
        return analyze(sql, connectionId, queryTimeoutSeconds, null);
    }

    /** Submit an EXPLAIN with per-request timeout and maxRows overrides. */
    public String analyze(String sql, String connectionId, Integer queryTimeoutSeconds, Integer maxRows) {
        return analyze(sql, connectionId, queryTimeoutSeconds, maxRows, null);
    }

    /** Submit an EXPLAIN with all options including analyzeMode. */
    public String analyze(String sql, String connectionId, Integer queryTimeoutSeconds,
                          Integer maxRows, String analyzeMode) {
        return analyze(sql,connectionId,queryTimeoutSeconds,maxRows,analyzeMode,null);
    }
    public String analyze(String sql,String connectionId,Integer timeout,Integer maxRows,String mode,String sessionId) {
        if(mode!=null && !Set.of("estimated","actual").contains(mode)) throw new IllegalArgumentException("INVALID_ANALYZE_MODE");
        return submit(sql,connectionId,Kind.ANALYZE,timeout,maxRows,mode==null?"estimated":mode,sessionId);
    }

    /**
     * 请求取消一次执行。
     *
     * @return {@code true} 当且仅当这次调用发布的 CANCELLED **赢得了终态闩锁**——
     *         即没有别的发布先占住终态；{@code false} 表示本次取消**未生效**：
     *         id 不存在、调用时已是终态、或并发 worker 在下面的判定与发布之间先
     *         赢了闩锁（例如它已 COMPLETED）。HTTP 层据此回 404——语义是「没有
     *         生效」而非「失败」，调用方不得当作错误（见 PR-2 T12）。
     */
    public boolean cancel(String executionId) {
        ExecutionContext ctx = executions.get(executionId);
        if (ctx == null) return false;

        // (C) 终态闩锁快速路径：调用时已是终态就直接 false，且不触碰
        // statement / future（执行已结束，无需再取消）。真正的判定在 publish
        // 的锁内读-判-写，返回值也取自它的结果。
        Published current = ctx.published;
        if (current.status() != null && current.status().isTerminal()) {
            return false;
        }

        if(ctx.ordinaryOracle)return cancelOrdinaryOracle(ctx,"CANCELLED_BY_USER");
        if(ctx.nativePlan) {
            if(ownerError(ctx)!=null) return false;
            return terminateNative(ctx,ExecutionStatus.CANCELLED,"CANCELLED_BY_USER");
        }
        ctx.cancelled = true;
        if (ctx.statement != null) {
            try {
                ctx.statement.cancel();
            } catch (SQLException e) {
                log.warn("Failed to cancel statement: {}", e.getMessage());
            }
        }
        if (ctx.future != null) {
            ctx.future.cancel(true);
        }
        // (B) 入口站：取消也发布带原因的完整终态 result（spec §4.3 (B) / §2 D2）。
        // worker 侧三处 CANCELLED 检查点已由 publishCancelled 统一发带原因的终态，
        // 所以入口站与三个检查点共用同一「终态必带 result」的发布形态（T6 已收口）。
        // publish 的返回值＝本次发布是否赢得闩锁：worker 若在上述判定与此处之间先赢，
        // 则本次取消未生效，如实返回 false，而不是谎报 true。
        return ctx.publish(ExecutionStatus.CANCELLED,
            errorResult(executionId, ExecutionStatus.CANCELLED, "Cancelled by user request"));
    }

    public ExecutionResult getStatus(String executionId) {
        ExecutionContext ctx = executions.get(executionId);
        if (ctx == null) return null;

        if(ctx.nativePlan && ctx.ownerAdmitted && ownerError(ctx)!=null) {
            terminateNative(ctx,ExecutionStatus.FAILED,"PLAN_OWNER_CHANGED");
            return errorResult(ctx.executionId,ExecutionStatus.FAILED,"Plan owner changed").withCapture(ctx.capture).withFailureReason("PLAN_OWNER_CHANGED");
        }
        // Read the snapshot ONCE into a local. Two reads of `published` could
        // straddle a publish and pair a stale status with a fresh result —
        // that is exactly the gap this fix closes.
        Published p = ctx.published;
        if (p.result() != null) {
            if(ctx.ordinaryOracle) {
                reconcileOrdinary(ctx);
                String reason=ctx.networkReason.get();
                if(reason==null)reason=ctx.ordinaryReason;
                if(reason==null&&pendingNetworkCallbacks(ctx)>0)reason="NETWORK_TIMEOUT_CALLBACK_PENDING";
                if(reason==null&&ctx.cancelWorkers>0)reason="CANCEL_SIGNAL_PENDING";
                if(reason==null&&!ctx.workerFinished&&p.status().isTerminal())reason="RESOURCE_CLEANUP_PENDING";
                return reason==null?p.result():p.result().withFailureReason(reason);
            }
            if(ctx.nativePlan && ctx.cleanupReason!=null && p.result().planCapture()!=null) {
                var capture=p.result().planCapture();var issues=new ArrayList<>(capture.issues());
                issues.add(new PlanCapture.Issue(ctx.cleanupReason,null,"Resource cleanup or cancellation status; not proof of physical server stop"));
                return p.result().withCapture(new PlanCapture(capture.version(),capture.requestedMode(),capture.attemptedMode(),capture.submittedSql(),capture.explainSql(),capture.connectionId(),capture.sessionId(),capture.dialect(),capture.serverProduct(),capture.serverVersion(),capture.capturedAt(),capture.capabilities(),capture.parseStatus(),issues,capture.details()));
            }
            return p.result();
        }

        // Capture assembly precedes receipt persistence and terminal publication.
        // A pending response must not pair the completed evidence with a null tree.
        PlanCapture pendingCapture=ctx.capture;
        if(pendingCapture!=null&&!p.status().isTerminal()&&pendingCapture.details()!=null)
            pendingCapture=new PlanCapture(pendingCapture.version(),pendingCapture.requestedMode(),pendingCapture.attemptedMode(),
                pendingCapture.submittedSql(),pendingCapture.explainSql(),pendingCapture.connectionId(),pendingCapture.sessionId(),
                pendingCapture.dialect(),pendingCapture.serverProduct(),pendingCapture.serverVersion(),null,pendingCapture.capabilities(),"NOT_STARTED",List.of());
        return new ExecutionResult(
            executionId, p.status(),
            null, null, 0, null, null, null, null
        ).withCapture(pendingCapture);
    }

    public record PlanObservation(String sourceState,String errorReason,ExecutionResult result) {}
    public PlanObservation resolvePlanObservation(String executionId,String sql,String connectionId,String sessionId) {
        if(executionId==null) return new PlanObservation("UNVERIFIED","UNVERIFIED_PLAN_SOURCE",null);
        ExecutionContext ctx=executions.get(executionId);
        if(ctx==null) return new PlanObservation("UNAVAILABLE","PLAN_SOURCE_UNAVAILABLE",null);
        synchronized(ctx) {
            if(ctx.hasTerminalTime && ticker.getAsLong()-ctx.terminalAt>=RETENTION_NANOS)
                return new PlanObservation("EXPIRED","PLAN_SOURCE_EXPIRED",null);
            if(ctx.kind!=Kind.ANALYZE || !Objects.equals(ctx.sql,sql) || !Objects.equals(ctx.connectionId,connectionId) || !Objects.equals(ctx.sessionId,sessionId))
                return new PlanObservation("MISMATCH","PLAN_SOURCE_MISMATCH",null);
            if(ctx.nativePlan && ownerError(ctx)!=null)return new PlanObservation("MISMATCH","PLAN_OWNER_CHANGED",null);
            var p=ctx.published;
            if(p.status()!=ExecutionStatus.COMPLETED || p.result()==null || p.result().plan()==null)
                return new PlanObservation("UNAVAILABLE","PLAN_SOURCE_UNAVAILABLE",null);
            if(p.result().planCapture()==null) return new PlanObservation("UNVERIFIED","UNVERIFIED_PLAN_SOURCE",null);
            return new PlanObservation("VERIFIED",null,p.result());
        }
    }

    /** Retry a failed receipt write only from the exact still-retained server result. */
    public ExecutionResult retryObservationReceipt(String executionId, String sql, String connectionId, String sessionId) {
        ExecutionContext ctx = executions.get(executionId);
        if (ctx == null) throw new PlanObservationReceiptStore.Failure(410, "SNAPSHOT_OBSERVATION_EXPIRED");
        synchronized (ctx) {
            PlanObservation resolved = resolvePlanObservation(executionId, sql, connectionId, sessionId);
            if (!"VERIFIED".equals(resolved.sourceState()) || resolved.result() == null)
                throw new PlanObservationReceiptStore.Failure(410, "SNAPSHOT_OBSERVATION_EXPIRED");
            if (resolved.result().observationReceipt() == null || !"FAILED".equals(resolved.result().observationReceipt().status()))
                throw new PlanObservationReceiptStore.Failure(409, "SNAPSHOT_RETRY_NOT_APPLICABLE");
            if (observationReceiptStore == null)
                throw new PlanObservationReceiptStore.Failure(503, "SNAPSHOT_RECEIPT_STORE_UNAVAILABLE");
            var saved = observationReceiptStore.save(resolved.result(), sessionId, connectionId, ctx.capture.dialect());
            if (!"SAVED".equals(saved.status()))
                throw new PlanObservationReceiptStore.Failure(503, saved.errorReason());
            ExecutionResult updated = resolved.result().withObservationReceipt(
                    new ExecutionResult.ObservationReceipt("SAVED", saved.receiptId(), null));
            ctx.published = new Published(ctx.published.status(), updated);
            return updated;
        }
    }

    // ─── Internals ─────────────────────────────────────────────────────────

    private String submit(String sql, String connectionId, Kind kind,
                          Integer queryTimeoutSeconds, Integer maxRows, String analyzeMode) {
        return submit(sql,connectionId,kind,queryTimeoutSeconds,maxRows,analyzeMode,null);
    }
    private String submit(String sql,String connectionId,Kind kind,Integer queryTimeoutSeconds,Integer maxRows,String analyzeMode,String sessionId) {
        String executionId = UUID.randomUUID().toString();
        ExecutionContext ctx = new ExecutionContext(executionId, sql, connectionId, kind, analyzeMode);
        ctx.observationReceiptStore = observationReceiptStore;
        ctx.ticker = ticker;
        ctx.sessionId=sessionId;
        if(kind==Kind.EXECUTE) {
            var submittedOwner=connectionService.getById(connectionId).orElse(null);
            if(submittedOwner!=null&&"oracle".equalsIgnoreCase(submittedOwner.dialect())) {
                ctx.ordinaryOracle=true;ctx.ordinaryDialect=submittedOwner.dialect();
                ctx.ordinaryRevision=com.bitdata.sqlworkbench.connection.ConnectionIdentity.revision(submittedOwner);
            }
        }
        if(kind==Kind.ANALYZE) {
            var configuration=connectionService.getById(connectionId);
            if(configuration.isPresent() && Set.of("sqlserver","oracle").contains(configuration.get().dialect())) {
                ctx.nativePlan=true;
                ctx.owner=configuration.get();
                ctx.ownerRevision=com.bitdata.sqlworkbench.connection.ConnectionIdentity.revision(ctx.owner);
                ctx.deadlineNanos=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(nativeBudgetMillis);
                if(sessionId!=null) {
                    var session=sessionStore==null?null:sessionStore.findById(sessionId).orElse(null);
                    ctx.sessionInstance=session==null?null:session.workspaceInstanceId();
                }
            }
        }
        if(kind==Kind.ANALYZE) ctx.capture=new PlanCapture(1,analyzeMode,null,sql,null,connectionId,sessionId,"unknown",null,null,null,null,"NOT_STARTED",List.of());

        ctx.effectiveTimeoutSeconds = (queryTimeoutSeconds != null && queryTimeoutSeconds > 0)
            ? queryTimeoutSeconds
            : this.queryTimeoutSeconds;
        ctx.effectiveMaxRows = (maxRows != null && maxRows > 0)
            ? Math.min(maxRows, MAX_ROWS_UPPER_BOUND)
            : this.defaultMaxRows;

        ctx.publish(ExecutionStatus.QUEUED, null);
        synchronized (admissionLock) {
            if(stopping)throw new IllegalStateException("APPLICATION_SHUTDOWN");
            removeExpired();
            if (executions.size() >= CAPACITY) throw new ExecutionCapacityException();
            executions.put(executionId, ctx);
        if(ctx.nativePlan) {
            String ownerFailure=ownerError(ctx);
            if(ownerFailure!=null) {
                ctx.exitFinished=true;
                ctx.publish(ExecutionStatus.FAILED,errorResult(executionId,ExecutionStatus.FAILED,ownerFailure).withFailureReason(ownerFailure));
                return executionId;
            }
            ctx.ownerAdmitted=true;
            ctx.deadlineTask=planDeadlines.schedule(()->terminateNative(ctx,ExecutionStatus.TIMED_OUT,"PLAN_TOTAL_BUDGET_EXCEEDED"),nativeBudgetMillis,TimeUnit.MILLISECONDS);
        }
        try {
            Future<?> future = executor.submit(() -> run(ctx));
            synchronized(ctx){ctx.future = future;
                if(ctx.ordinaryOracle&&ctx.cancelled)future.cancel(false);
            }
        } catch (RejectedExecutionException e) {
            ctx.exitFinished=true;ctx.workerFinished=true;ctx.ordinaryCleanup="NO_RESOURCES";
            ctx.publish(ExecutionStatus.FAILED, errorResult(executionId,
                ExecutionStatus.FAILED,
                "Execution pool exhausted. Please wait for running queries to complete."));
        }

        return executionId;
        }
    }

    private void run(ExecutionContext ctx) {
        try {
            synchronized(ctx) {
                if(ctx.cancelled)return;
                ctx.exitStarted=true;
                if(ctx.ordinaryOracle){ctx.ordinaryStarted=true;ctx.ordinaryCleanup="PENDING";}
            }
            if (ctx.kind == Kind.EXECUTE) {
                runSelect(ctx);
            } else {
                runAnalyze(ctx);
            }
        } catch (Throwable t) {
            // 兜底终态（规则 #1 同类扫描第 5 站，recon 2026-10-02）：runSelect /
            // runAnalyze 的 try-with-resources 之外仍有可抛路径 —— lookupDialect 对
            // 已删除的 connectionId 抛 IllegalStateException 就在 publish(RUNNING)
            // 之后。此前这类执行永远停在 RUNNING：getStatus 永不返回终态（前端轮询
            // 不终止），(D) 的回收谓词也永不命中（条目泄漏）。
            //
            // 捕获 Throwable 而非 Exception 是刻意的：run() 承诺「进入它的执行必然
            // 落终态」；Error（AssertionError / OOM 等）若逃逸同样会把执行留在
            // RUNNING，与受检/运行时异常无异地违反该承诺，故一并兜底。
            // 终态闩锁 (C) 保证它不会覆盖已发布的终态（publish 对已终态 no-op）。
            // 发布带原因的 FAILED（规则 #2：可观测降级），而不是只 log 后照常返回。
            ctx.publish(ExecutionStatus.FAILED, errorResult(ctx.executionId,
                ExecutionStatus.FAILED, "Execution failed: " + t.getMessage()));
            log.error("Execution {} failed outside the run try-block: {}",
                ctx.executionId, t.getMessage(), t);
        } finally {
            synchronized(ctx){ctx.exitFinished=true;}
            ctx.statement = null;
            if(ctx.ordinaryOracle)synchronized(ctx){
                ctx.workerFinished=true;
                if(ctx.ordinaryWorkerTicket!=null)ctx.ordinaryWorkerTicket.release();
                if(ctx.ordinaryConnectionTicket!=null&&ctx.ordinaryConnectionTicket.isUnconfirmed()) {
                    ctx.ordinaryCleanup="UNCONFIRMED";ctx.ordinaryReason="RESOURCE_CLEANUP_UNCONFIRMED";
                } else if(!"UNCONFIRMED".equals(ctx.ordinaryCleanup))ctx.ordinaryCleanup="CLOSED";
                reconcileOrdinary(ctx);
            }
            if(ctx.deadlineTask!=null)ctx.deadlineTask.cancel(false);
        }
    }

    /** Terminal eligibility only; removeExpired also requires the full retention window. */
    static boolean isReclaimable(Published published) {
        return published.status() != null && published.status().isTerminal();
    }

    /** Native plans: terminal status and reference-local release do not settle shared owners. */
    private static boolean nativeOwnersSettled(ExecutionContext ctx) {
        return ctx.exitFinished && ctx.cancelWorkers==0 && ctx.exitCancelWorkers==0 && pendingNetworkCallbacks(ctx)==0
            && (ctx.nativeTicket==null || Boolean.TRUE.equals(ctx.nativeTicket.resourceSnapshot().get("entryReleased")));
    }

    void removeExpired() {
        synchronized (admissionLock) {
            long now = ticker.getAsLong();
            executions.entrySet().removeIf(entry -> {
                ExecutionContext ctx = entry.getValue();
                synchronized (ctx) {
                    reconcileOrdinary(ctx);
                    return (!ctx.ordinaryOracle || (ctx.workerFinished && ctx.cancelWorkers==0 && pendingNetworkCallbacks(ctx)==0 && !"UNCONFIRMED".equals(ctx.ordinaryCleanup))) && (!ctx.nativePlan || nativeOwnersSettled(ctx)) && isReclaimable(ctx.published) && ctx.hasTerminalTime && now - ctx.terminalAt >= RETENTION_NANOS;
                }
            });
        }
    }

    @jakarta.annotation.PreDestroy
    public synchronized void beginShutdown() {
        synchronized(admissionLock){if(stopping)return;stopping=true;}
        cleanup.shutdownNow();planDeadlines.shutdownNow();networkTimeouts.beginShutdown();
        if(planCapabilityService!=null)planCapabilityService.beginShutdown();
        // Cancel every queued task before interrupting running work can free a worker.
        for(var ctx:executions.values())synchronized(ctx){
            if(!ctx.exitStarted){ctx.cancelled=true;ctx.exitFinished=true;if(ctx.future!=null)ctx.future.cancel(false);}
        }
        for(var ctx:executions.values()) synchronized(ctx) {
            if(!ctx.exitStarted)ctx.exitFinished=true;
            if(ctx.ordinaryOracle)cancelOrdinaryOracle(ctx,"APPLICATION_SHUTDOWN");
            else if(ctx.nativePlan) {
                terminateNative(ctx,ExecutionStatus.CANCELLED,"APPLICATION_SHUTDOWN");
                if(!ctx.exitStarted)ctx.cleanupReason=null;
                if(ctx.future!=null)ctx.future.cancel(false);
            } else {
                if(ctx.published.status().isTerminal())continue;
                ctx.cancelled=true;ctx.publish(ExecutionStatus.CANCELLED,errorResult(ctx.executionId,ExecutionStatus.CANCELLED,"APPLICATION_SHUTDOWN"));
                Statement statement=ctx.statement;if(ctx.future!=null)ctx.future.cancel(true);
                if(statement!=null) {
                    ctx.exitCancelWorkers++;
                    try{exitCancellation.execute(()->{
                        try{statement.cancel();}catch(Exception failure){ctx.exitReason="CANCEL_SIGNAL_FAILED";}
                        finally{synchronized(ctx){ctx.exitCancelWorkers--;}}
                    });}catch(RejectedExecutionException failure){ctx.exitCancelWorkers--;ctx.exitReason="CANCEL_SIGNAL_REJECTED";}
                }
            }
        }
        executor.shutdown();ordinaryCancellation.shutdown();exitCancellation.shutdown();
        planCancellation.shutdown();planCleanup.shutdown();planMetadata.shutdown();
    }
    /** Nonblocking, local snapshot. Caller owns its absolute wait budget. */
    public Map<String,Object> shutdownSnapshot() {
        var rows=new ArrayList<Object>();boolean drained=true,issues=false;
        for(var ctx:executions.values())synchronized(ctx){
            reconcileOrdinary(ctx);
            boolean pending=!ctx.exitFinished||ctx.exitCancelWorkers>0||ctx.cancelWorkers>0||pendingNetworkCallbacks(ctx)>0;
            boolean unconfirmed="UNCONFIRMED".equals(ctx.ordinaryCleanup)||ctx.ordinaryStatementCloseFailed
                ||ctx.exitConnectionOpened&&!ctx.exitConnectionClosed
                ||ctx.nativeTicket!=null&&ctx.nativeTicket.isUnconfirmed()
                ||ctx.nativePlan&&!nativeOwnersSettled(ctx);
            boolean confirmed=ctx.published.status().isTerminal()&&!pending&&!unconfirmed;
            drained&=confirmed;String reason=ctx.networkReason.get()!=null?ctx.networkReason.get():ctx.exitReason!=null?ctx.exitReason:ctx.ordinaryReason;
            issues|=reason!=null;
            rows.add(Map.of("executionId",ctx.executionId,"status",ctx.published.status().name(),"nativePlan",ctx.nativePlan,
                "workerFinished",ctx.exitFinished,"cancelWorkers",ctx.exitCancelWorkers+ctx.cancelWorkers+pendingNetworkCallbacks(ctx),"cleanupConfirmed",confirmed,
                "errorReason",reason!=null?reason:!confirmed?"SHUTDOWN_CLEANUP_UNCONFIRMED":"NONE"));
        }
        var capability=planCapabilityService==null?Map.<String,Object>of("drained",true):planCapabilityService.shutdownSnapshot();
        drained&=Boolean.TRUE.equals(capability.get("drained"));
        return Map.of("capabilityProbes",capability,"stopping",stopping,"drained",stopping&&drained,"degraded",!stopping||!drained||issues,
            "errorReason",!stopping||!drained?"SHUTDOWN_CLEANUP_UNCONFIRMED":issues?"SHUTDOWN_CANCEL_SIGNAL_FAILED":"NONE","tasks",rows);
    }
    public void shutdown(){beginShutdown();}

    /**
     * S2 — validate the user SQL is a single SELECT BEFORE any connection is
     * opened. For analyze() this validates the user's SQL, not the
     * strategy-wrapped EXPLAIN text.
     */
    private boolean passesSelectGuard(ExecutionContext ctx, String dialect) {
        try {
            selectOnlyGuard.requireSingleSelect(ctx.sql, dialect);
            return true;
        } catch (IllegalArgumentException | IllegalStateException e) {
            var failure=errorResult(ctx.executionId, ExecutionStatus.FAILED, e.getMessage());
            var unsupported=com.bitdata.sqlworkbench.sql.SqlSerializationException.find(e);
            if(unsupported!=null)failure=failure.withFailureReason(unsupported.reason());
            else if(ctx.ordinaryOracle)failure=failure.withFailureReason("SQL_SELECT_GUARD_REJECTED");
            ctx.publish(ExecutionStatus.FAILED, failure);
            return false;
        }
    }

    /** Data-mode path: run SELECT, materialize rows. NO EXPLAIN here. */
    private void runSelect(ExecutionContext ctx) {
        ctx.publish(ExecutionStatus.RUNNING, null);
        String dialect = ctx.ordinaryOracle?ctx.ordinaryDialect:lookupDialect(ctx.connectionId);
        if (!passesSelectGuard(ctx, dialect)) return;
        long startTime = System.currentTimeMillis();

        log.info("Executing SQL on connection {}: {}", ctx.connectionId, ctx.sql);

        try (Connection conn = openOrdinary(ctx)) {
            if(ctx.ordinaryOracle) {
                long millis=(long)ctx.effectiveTimeoutSeconds*1000L+15000L;
                if(millis<=0||millis>Integer.MAX_VALUE)throw new SQLException("ORACLE_NETWORK_TIMEOUT_RANGE_UNSUPPORTED");
                try {
                    ctx.networkRegistration=networkTimeouts.register(ctx.ordinaryConnectionTicket,ctx.networkReason::set);
                    conn.setNetworkTimeout(ctx.networkRegistration,(int)millis);
                }
                catch(SQLException|RuntimeException unavailable){throw new SQLException("ORACLE_NETWORK_TIMEOUT_SETUP_FAILED",unavailable);}
            }
            DatabaseStrategy strategy = strategyFactory.get(dialect);
            if (strategy.supportsReadOnly()) {
                strategy.configureReadOnly(conn);
            }

            try (Statement stmt = conn.createStatement()) {
                ctx.statement = stmt;
                stmt.setQueryTimeout(ctx.effectiveTimeoutSeconds);
                stmt.setMaxRows(ctx.effectiveMaxRows + 1);

                if (ctx.cancelled) {
                    publishCancelled(ctx, "Cancelled before execution started", startTime);
                    return;
                }

                boolean isResultSet = stmt.execute(ctx.sql);

                if (!isResultSet) {
                    ctx.publish(ExecutionStatus.FAILED, errorResult(ctx.executionId,
                        ExecutionStatus.FAILED,
                        "Only SELECT statements are supported"));
                    return;
                }

                try (ResultSet rs = stmt.getResultSet()) {
                    ResultSetMetaData meta = rs.getMetaData();
                    int colCount = meta.getColumnCount();

                    List<ExecutionResult.ColumnInfo> columns = new ArrayList<>();
                    for (int i = 1; i <= colCount; i++) {
                        columns.add(new ExecutionResult.ColumnInfo(
                            ctx.ordinaryOracle ? meta.getColumnLabel(i) : meta.getColumnName(i),
                            meta.getColumnTypeName(i)
                        ));
                    }

                    List<List<Object>> rows = new ArrayList<>();
                    boolean truncated = false;
                    while (true) {
                        if (ctx.cancelled) {
                            publishCancelled(ctx, "Cancelled while fetching rows", startTime);
                            return;
                        }
                        boolean hasNext = rs.next();
                        if (ctx.cancelled) {
                            publishCancelled(ctx, "Cancelled while fetching rows", startTime);
                            return;
                        }
                        if (!hasNext) break;
                        if (rows.size() == ctx.effectiveMaxRows) {
                            truncated = true;
                            break;
                        }
                        List<Object> row = new ArrayList<>();
                        for (int i = 1; i <= colCount; i++) {
                            row.add(rs.getObject(i));
                        }
                        rows.add(row);
                    }

                    long duration = System.currentTimeMillis() - startTime;
                    completeSelect(ctx, new ExecutionResult(
                        ctx.executionId, ExecutionStatus.COMPLETED,
                        columns, rows, rows.size(),
                        new ExecutionResult.Timing(duration),
                        null,   // plan — not produced by execute; comes from analyze()
                        null,   // planError
                        null, null, null, false,
                        new ResultCompleteness(truncated ? ResultCompleteness.State.TRUNCATED : ResultCompleteness.State.COMPLETE,
                            ctx.effectiveMaxRows, null), java.time.Instant.now()
                    ));
                    log.info("Execution {} completed: {} rows in {}ms",
                        ctx.executionId, rows.size(), duration);
                }
            }
        } catch (com.bitdata.sqlworkbench.connection.CredentialFailure e) {
            ctx.publish(ExecutionStatus.FAILED, errorResultWithTiming(ctx.executionId,ExecutionStatus.FAILED,
                e.getMessage(),System.currentTimeMillis()-startTime).withCredentialFailure(e.code));
        } catch (SQLTimeoutException e) {
            ctx.publish(ExecutionStatus.TIMED_OUT, errorResultWithTiming(ctx.executionId,
                ExecutionStatus.TIMED_OUT,
                "Query timed out after " + ctx.effectiveTimeoutSeconds + "s",
                System.currentTimeMillis() - startTime));
            log.warn("Execution {} timed out", ctx.executionId);
        } catch (SQLException e) {
            // Per-request timeout may surface as a plain SQLException carrying the
            // server's cancel-signal (PG "canceling statement due to user request",
            // sqlstate 57014) rather than SQLTimeoutException. Classify it so the
            // user sees TIMED_OUT, not a misleading FAILED (Follow-up 2/3).
            ExecutionStatus resolved = QueryTimeoutClassifier.isTimeout(e, dialect)
                ? ExecutionStatus.TIMED_OUT : ExecutionStatus.FAILED;
            String message = resolved == ExecutionStatus.TIMED_OUT
                ? "Query timed out after " + ctx.effectiveTimeoutSeconds + "s"
                : e.getMessage();
            ExecutionResult failure=errorResultWithTiming(ctx.executionId, resolved, message,
                System.currentTimeMillis() - startTime);
            if(ctx.ordinaryOracle&&e.getMessage()!=null&&e.getMessage().startsWith("ORACLE_NETWORK_TIMEOUT_"))failure=failure.withFailureReason(e.getMessage());
            if(ctx.ordinaryOracle&&"CONNECTION_CHANGED".equals(e.getMessage()))failure=failure.withFailureReason("EXECUTION_OWNER_CHANGED");
            if(e instanceof com.bitdata.sqlworkbench.connection.WorkloadBusyException) failure=failure.withFailureReason("WORKLOAD_BUSY");
            ctx.publish(resolved, failure);
            log.warn("Execution {} {}: {}", ctx.executionId, resolved, e.getMessage());
        } catch (Exception e) {
            ctx.publish(ExecutionStatus.FAILED, errorResultWithTiming(ctx.executionId,
                ExecutionStatus.FAILED,
                e.getMessage(), System.currentTimeMillis() - startTime));
            log.error("Execution {} failed (non-SQL): {}", ctx.executionId, e.getMessage(), e);
        }
    }

    /** Performance-mode path: run EXPLAIN (ANALYZE true, FORMAT JSON), parse to PlanNode tree. */
    private void runAnalyze(ExecutionContext ctx) {
        ctx.publish(ExecutionStatus.RUNNING, null);
        String dialect = lookupDialect(ctx.connectionId);
        ctx.capture=new PlanCapture(1,ctx.analyzeMode,null,ctx.sql,null,ctx.connectionId,ctx.sessionId,dialect,null,null,null,null,"NOT_STARTED",List.of());
        if (!passesSelectGuard(ctx, dialect)) return;
        if(!explainServiceFactory.supports(dialect)) {
            ctx.publish(ExecutionStatus.FAILED,errorResult(ctx.executionId,ExecutionStatus.FAILED,
                "This application does not support the requested plan channel").withCredentialFailure("APPLICATION_CHANNEL_UNSUPPORTED"));
            return;
        }
        if(ctx.nativePlan) { runNativeAnalyze(ctx); return; }
        long startTime = System.currentTimeMillis();

        log.info("Analyzing SQL on connection {}: {}", ctx.connectionId, ctx.sql);

        try (Connection conn = trackOrdinary(connectionService.openJdbcConnection(ctx.connectionId),ctx)) {
            PlanCapabilities capabilities;
            try { capabilities=PlanCapabilityService.inspect(ctx.connectionId,dialect,conn,explainServiceFactory); }
            catch(Exception probeFailure) { capabilities=PlanCapabilityService.unknown(ctx.connectionId,dialect,"CAPABILITY_PROBE_FAILED"); }
            ctx.capture=new PlanCapture(1,ctx.analyzeMode,null,ctx.sql,null,ctx.connectionId,ctx.sessionId,dialect,
                capabilities.serverProduct(),capabilities.serverVersion(),null,capabilities,"NOT_STARTED",List.of());
            if(!capabilities.supports(ctx.analyzeMode)) {
                String reason=capabilities.errorReason()==null?"APPLICATION_CHANNEL_UNSUPPORTED":capabilities.errorReason();
                ctx.publish(ExecutionStatus.FAILED,errorResult(ctx.executionId,ExecutionStatus.FAILED,"Plan capability unavailable: "+reason).withCredentialFailure(reason));
                return;
            }
            DatabaseStrategy strategy = strategyFactory.get(dialect);
            if (strategy.supportsReadOnly()) {
                strategy.configureReadOnly(conn);
            }

            var environment=PlanEvidence.environment(conn,dialect,ctx.effectiveTimeoutSeconds);

            try (Statement stmt = conn.createStatement()) {
                ctx.statement = stmt;
                stmt.setQueryTimeout(ctx.effectiveTimeoutSeconds);

                if (ctx.cancelled) {
                    publishCancelled(ctx, "Cancelled before EXPLAIN started", startTime);
                    return;
                }

                String explainSql;
                if ("actual".equals(ctx.analyzeMode)) {
                    explainSql = strategy.getAnalyzeSql(ctx.sql);
                } else {
                    explainSql = strategy.getExplainSql(ctx.sql);
                }
                String channel="mysql".equals(dialect)?("actual".equals(ctx.analyzeMode)?"MYSQL_TREE":"MYSQL_JSON"):"POSTGRESQL_JSON";
                ctx.capture=new PlanCapture(1,ctx.analyzeMode,ctx.analyzeMode,ctx.sql,explainSql,ctx.connectionId,ctx.sessionId,dialect,
                    capabilities.serverProduct(),capabilities.serverVersion(),null,capabilities,"NOT_STARTED",List.of());
                try (ResultSet rs = stmt.executeQuery(explainSql)) {
                    ExecutionResult.PlanNode plan = null;
                    String planError = null;
                    if (rs.next()) {
                        String explainJson = rs.getString(1);
                        ExplainService explainService = explainServiceFactory.get(strategy.dialect());
                        boolean shape=rs.getMetaData()!=null && rs.getMetaData().getColumnCount()==1 && !rs.next();
                        ExplainService.Parsed parsed=shape?explainService.parseDetailed(explainJson,strategy.dialect(),channel):ExplainService.failed("PLAN_RESULT_SHAPE_UNSUPPORTED");
                        plan=parsed.plan();
                        ctx.capture=new PlanCapture(1,ctx.analyzeMode,ctx.analyzeMode,ctx.sql,explainSql,ctx.connectionId,ctx.sessionId,dialect,
                            capabilities.serverProduct(),capabilities.serverVersion(),java.time.Instant.now().toString(),capabilities,parsed.status(),parsed.issues()).withDetails(PlanEvidence.capture(explainJson,channel,plan,environment));
                        if (plan == null) {
                            planError = "EXPLAIN output could not be parsed as a plan tree";
                        }
                    } else {
                        planError = "EXPLAIN returned no rows";
                        ctx.capture=new PlanCapture(1,ctx.analyzeMode,ctx.analyzeMode,ctx.sql,explainSql,ctx.connectionId,ctx.sessionId,dialect,
                            capabilities.serverProduct(),capabilities.serverVersion(),null,capabilities,"FAILED",
                            List.of(new PlanCapture.Issue("PLAN_PARSE_FAILED",null,planError)));
                    }

                    String detailsError=ctx.capture.details()==null?null:ctx.capture.details().errorReason();
                    if(detailsError!=null) {
                        var issues=new java.util.ArrayList<>(ctx.capture.issues());
                        issues.add(new PlanCapture.Issue(detailsError,null,"Plan evidence or environment capture incomplete"));
                        ctx.capture=new PlanCapture(ctx.capture.version(),ctx.capture.requestedMode(),ctx.capture.attemptedMode(),ctx.capture.submittedSql(),ctx.capture.explainSql(),ctx.capture.connectionId(),ctx.capture.sessionId(),ctx.capture.dialect(),ctx.capture.serverProduct(),ctx.capture.serverVersion(),ctx.capture.capturedAt(),ctx.capture.capabilities(),ctx.capture.parseStatus(),issues,ctx.capture.details());
                    }
                    String resultReason="FAILED".equals(ctx.capture.parseStatus())?(ctx.capture.issues().isEmpty()?"PLAN_PARSE_FAILED":ctx.capture.issues().get(0).code()):"PARTIAL".equals(ctx.capture.parseStatus())?"PLAN_PARTIAL":detailsError;
                    long duration = System.currentTimeMillis() - startTime;
                    ctx.publish(ExecutionStatus.COMPLETED, new ExecutionResult(
                        ctx.executionId, ExecutionStatus.COMPLETED,
                        null, null, 0,
                        new ExecutionResult.Timing(duration),
                        plan, planError, null, null,
                        resultReason,
                        !"COMPLETE".equals(ctx.capture.parseStatus()) || detailsError!=null
                    ));
                    log.info("Analyze {} completed: plan={} in {}ms",
                        ctx.executionId, plan != null ? "captured" : "null", duration);
                }
            }
        } catch (com.bitdata.sqlworkbench.connection.CredentialFailure e) {
            ctx.publish(ExecutionStatus.FAILED, errorResultWithTiming(ctx.executionId,ExecutionStatus.FAILED,
                e.getMessage(),System.currentTimeMillis()-startTime).withCredentialFailure(e.code));
        } catch (SQLTimeoutException e) {
            ctx.publish(ExecutionStatus.TIMED_OUT, analyzeErrorResult(ctx.executionId,
                ExecutionStatus.TIMED_OUT,
                "EXPLAIN timed out after " + ctx.effectiveTimeoutSeconds + "s",
                System.currentTimeMillis() - startTime));
            log.warn("Analyze {} timed out", ctx.executionId);
        } catch (SQLException e) {
            // Per-request timeout may surface as a plain SQLException carrying the
            // server's cancel-signal rather than SQLTimeoutException (Follow-up 2/3).
            ExecutionStatus resolved = QueryTimeoutClassifier.isTimeout(e, dialect)
                ? ExecutionStatus.TIMED_OUT : ExecutionStatus.FAILED;
            String message = resolved == ExecutionStatus.TIMED_OUT
                ? "EXPLAIN timed out after " + ctx.effectiveTimeoutSeconds + "s"
                : "EXPLAIN failed: " + e.getMessage();
            log.warn("Analyze {} {}: {}", ctx.executionId, resolved, e.getMessage(), e);
            // This log used to sit BETWEEN the status write and the result write,
            // and that gap is what getStatus observed as "terminal status + null
            // error" (defect A). status and result are now published together, so
            // the log's position no longer matters; it stays here for a minimal
            // diff. Side effect: during the log I/O getStatus now reads RUNNING
            // instead of a false terminal — strictly the safer read.
            ExecutionResult failure=analyzeErrorResult(ctx.executionId, resolved, message,
                System.currentTimeMillis() - startTime);
            if(e instanceof com.bitdata.sqlworkbench.connection.WorkloadBusyException) failure=failure.withFailureReason("WORKLOAD_BUSY");
            ctx.publish(resolved, failure);
        } catch (Exception e) {
            log.warn("Analyze {} failed (non-SQL): {}", ctx.executionId, e.getMessage(), e);
            ctx.publish(ExecutionStatus.FAILED, analyzeErrorResult(ctx.executionId,
                ExecutionStatus.FAILED,
                "EXPLAIN failed: " + e.getMessage(),
                System.currentTimeMillis() - startTime));
        }
    }

    /** Private live owner; historical receipts remain immutable offline facts. */
    private String ownerError(ExecutionContext ctx) {
        if(!ctx.nativePlan)return null;
        var current=connectionService.getById(ctx.connectionId).orElse(null);
        if(current==null || !Objects.equals(ctx.ownerRevision,com.bitdata.sqlworkbench.connection.ConnectionIdentity.revision(current))
            || current.readonly()!=ctx.owner.readonly() || !Objects.equals(current.keychainRef(),ctx.owner.keychainRef())
            || !Objects.equals(current.credentialState(),ctx.owner.credentialState()))return "CONNECTION_CHANGED";
        if(ctx.sessionId!=null) {
            var session=sessionStore==null?null:sessionStore.findById(ctx.sessionId).orElse(null);
            if(session==null || ctx.sessionInstance==null || !ctx.sessionInstance.equals(session.workspaceInstanceId()))return "SESSION_INSTANCE_CHANGED";
        }
        return null;
    }

    private void nativeCheckpoint(ExecutionContext ctx) {
        if(ctx.cancelled)throw new CancellationException("PLAN_CANCELLED");
        String owner=ownerError(ctx);if(owner!=null)throw new IllegalStateException(owner);
        if(System.nanoTime()>=ctx.deadlineNanos) {
            terminateNative(ctx,ExecutionStatus.TIMED_OUT,"PLAN_TOTAL_BUDGET_EXCEEDED");
            throw new CancellationException("PLAN_TOTAL_BUDGET_EXCEEDED");
        }
    }

    private int nativeTimeout(ExecutionContext ctx) {
        nativeCheckpoint(ctx);
        long seconds=TimeUnit.NANOSECONDS.toSeconds(ctx.deadlineNanos-System.nanoTime());
        if(seconds<1) {
            terminateNative(ctx,ExecutionStatus.TIMED_OUT,"PLAN_TOTAL_BUDGET_EXCEEDED");
            throw new CancellationException("PLAN_TOTAL_BUDGET_EXCEEDED");
        }
        return (int)Math.min(seconds,ctx.effectiveTimeoutSeconds);
    }

    /** Publish first; a blocked driver cancel never blocks the HTTP caller or deadline. */
    private boolean terminateNative(ExecutionContext ctx,ExecutionStatus status,String reason) {
        synchronized(ctx) {
            if(!ctx.publish(status,errorResult(ctx.executionId,status,reason).withFailureReason(reason)))return false;
            ctx.cancelled=true;
            ctx.cleanupReason="CLEANUP_PENDING";
            Statement statement=ctx.statement;
            if(statement==null){ctx.nativeSignal="NOT_REQUIRED";return true;}
            ctx.nativeSignal="PENDING";
            var ticket=ctx.nativeTicket==null?null:ctx.nativeTicket.retain();
            ctx.cancelWorkers++;
            try {
                planCancellation.execute(()->{
                    try {statement.cancel();ctx.nativeSignal="SENT";}
                    catch(SQLException|RuntimeException failure) {ctx.nativeSignal="FAILED";ctx.cleanupReason="CANCEL_SIGNAL_FAILED";}
                    finally {
                        synchronized(ctx) {ctx.cancelWorkers--;if(ctx.cancelWorkers==0&&"CANCEL_SIGNAL_PENDING".equals(ctx.cleanupReason))ctx.cleanupReason=null;}
                        if(ticket!=null)ticket.release();
                    }
                });
            } catch(RejectedExecutionException unavailable) {
                ctx.cancelWorkers--;if(ticket!=null)ticket.release();ctx.nativeSignal="REJECTED";ctx.cleanupReason="CANCEL_SIGNAL_UNAVAILABLE";
            }
            return true;
        }
    }

    private String finishNativeResources(ExecutionContext ctx,Connection connection,Statement setup,Statement query,String setting,boolean lost) {
        var held=ctx.nativeTicket==null?null:ctx.nativeTicket.retain();
        var task=new FutureTask<String>(()->{
            try {
                ctx.nativeConnectionLock.lock();
                try {
                    String state=SqlServerPlanCollector.closeResources(connection,setup,query,setting,lost);
                    synchronized(ctx) {ctx.cleanupReason="CONFIRMED".equals(state)?(ctx.cancelWorkers>0?"CANCEL_SIGNAL_PENDING":null):state;}
                    return state;
                } finally {ctx.nativeConnectionLock.unlock();}
            } finally {if(held!=null)held.release();}
        });
        try {planCleanup.execute(task);}
        catch(RejectedExecutionException unavailable) {
            // Capacity remains pinned; perform cleanup on the existing worker rather than abandon resources.
            task.run();ctx.cleanupReason="CLEANUP_EXECUTOR_UNAVAILABLE";
            return "CLEANUP_EXECUTOR_UNAVAILABLE";
        }
        try {return task.get(3,TimeUnit.SECONDS);}
        catch(TimeoutException timeout) {ctx.cleanupReason="CLEANUP_PENDING";return "CLEANUP_PENDING";}
        catch(InterruptedException interrupted) {Thread.currentThread().interrupt();ctx.cleanupReason="CLEANUP_PENDING";return "CLEANUP_PENDING";}
        catch(ExecutionException failure) {ctx.cleanupReason="PLAN_CLEANUP_FAILED";return "PLAN_CLEANUP_FAILED";}
    }

    private <T> T nativeMetadata(ExecutionContext ctx,Callable<T> work) throws Exception {
        var held=ctx.nativeTicket.retain();
        long deadline=Math.min(ctx.deadlineNanos,System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(metadataBudgetMillis));
        var task=new FutureTask<T>(()->{
            try {
                ctx.nativeConnectionLock.lock();
                try {
                    nativeCheckpoint(ctx);
                    if(System.nanoTime()>=deadline)throw new SQLTimeoutException("PLAN_METADATA_BUDGET_EXCEEDED");
                    return work.call();
                } finally {ctx.nativeConnectionLock.unlock();}
            } finally {held.release();}
        });
        try {planMetadata.execute(task);}
        catch(RejectedExecutionException busy) {held.release();throw new IllegalStateException("PLAN_METADATA_EXECUTOR_BUSY");}
        try {return task.get(Math.max(1,deadline-System.nanoTime()),TimeUnit.NANOSECONDS);}
        catch(TimeoutException timeout) {
            terminateNative(ctx,ExecutionStatus.TIMED_OUT,"PLAN_METADATA_BUDGET_EXCEEDED");
            throw new SQLTimeoutException("PLAN_METADATA_BUDGET_EXCEEDED");
        } catch(ExecutionException failure) {
            if(failure.getCause() instanceof Exception cause)throw cause;
            throw failure;
        }
    }

    private void runNativeAnalyze(ExecutionContext ctx) {
        if("oracle".equals(ctx.owner.dialect())) {runOracleAnalyze(ctx);return;}
        long started=System.currentTimeMillis();
        com.bitdata.sqlworkbench.connection.WorkloadLeaseManager.Ticket ticket=null;
        Connection connection=null;
        boolean transferred=false,lost=false;
        try {
            nativeCheckpoint(ctx);
            ticket=connectionService.acquireOrdinaryWorkload();
            synchronized(ctx) {ctx.nativeTicket=ticket;}
            nativeCheckpoint(ctx);
            long remaining=Math.max(1,TimeUnit.NANOSECONDS.toMillis(ctx.deadlineNanos-System.nanoTime()));
            ctx.nativeConnectionTicket=ticket.retain();
            connection=connectionService.openOrdinaryWithTimeout(ctx.connectionId,ctx.ownerRevision,remaining,ctx.nativeConnectionTicket);
            {
                nativeCheckpoint(ctx);
                Connection owned=connection;
                var capabilities=nativeMetadata(ctx,()->PlanCapabilityService.inspect(ctx.connectionId,"sqlserver",owned,explainServiceFactory,()->nativeCheckpoint(ctx)));
                ctx.capture=new PlanCapture(1,ctx.analyzeMode,null,ctx.sql,null,ctx.connectionId,ctx.sessionId,"sqlserver",
                    capabilities.serverProduct(),capabilities.serverVersion(),null,capabilities,"NOT_STARTED",List.of());
                if(!capabilities.supports(ctx.analyzeMode))throw new IllegalStateException(capabilities.errorReason()==null?"APPLICATION_CHANNEL_UNSUPPORTED":capabilities.errorReason());
                nativeCheckpoint(ctx);
                var environment=nativeMetadata(ctx,()->PlanEvidence.sqlServerEnvironment(owned,Math.min(5,nativeTimeout(ctx)),()->nativeCheckpoint(ctx)));
                nativeCheckpoint(ctx);
                ctx.capture=new PlanCapture(1,ctx.analyzeMode,ctx.analyzeMode,ctx.sql,ctx.sql,ctx.connectionId,ctx.sessionId,"sqlserver",
                    capabilities.serverProduct(),capabilities.serverVersion(),null,capabilities,"NOT_STARTED",List.of());
                transferred=true;
                var outcome=SqlServerPlanCollector.capture(connection,ctx.sql,"actual".equals(ctx.analyzeMode),()->nativeTimeout(ctx),
                    ()->nativeCheckpoint(ctx),statement->{synchronized(ctx){ctx.statement=statement;}},
                    (resource,setup,query,setting,transportLost)->finishNativeResources(ctx,resource,setup,query,setting,transportLost));
                nativeCheckpoint(ctx);
                var documents=outcome.documents();
                var parsed=documents.size()==1?documents.get(0):new SqlServerPlanParser.NativeParsed(outcome.status(),outcome.errorReason(),
                    documents.isEmpty()?null:documents.stream().map(SqlServerPlanParser.NativeParsed::rawPlan).collect(java.util.stream.Collectors.joining("\n")),null,List.of(),List.of());
                var details=SqlServerPlanParser.details(parsed,environment);
                var issues=new ArrayList<PlanCapture.Issue>();
                if(outcome.errorReason()!=null)issues.add(new PlanCapture.Issue(outcome.errorReason(),null,"Native plan unavailable or incomplete"));
                if(environment.errorReason()!=null)issues.add(new PlanCapture.Issue(environment.errorReason(),null,"Environment capture incomplete"));
                issues.add(new PlanCapture.Issue("SQLSERVER_METRIC_COMPARABILITY_LIMITED",null,"Thread facts are not per-loop metrics; full SQL mapping is unsupported"));
                ctx.capture=new PlanCapture(1,ctx.analyzeMode,ctx.analyzeMode,ctx.sql,ctx.sql,ctx.connectionId,ctx.sessionId,"sqlserver",
                    capabilities.serverProduct(),capabilities.serverVersion(),java.time.Instant.now().toString(),capabilities,outcome.status(),issues,details);
                // Close the dedicated physical connection before successful publication.
                synchronized(ctx) {ctx.nativeResult=new ExecutionResult(ctx.executionId,ExecutionStatus.COMPLETED,null,null,0,
                    new ExecutionResult.Timing(System.currentTimeMillis()-started),parsed.plan(),parsed.plan()==null?outcome.errorReason():null,null,null,
                    outcome.errorReason()!=null?outcome.errorReason():environment.errorReason()!=null?environment.errorReason():"SQLSERVER_METRIC_COMPARABILITY_LIMITED",true);}
            }
            nativeCheckpoint(ctx);
            ctx.publish(ExecutionStatus.COMPLETED,ctx.nativeResult);
        } catch(Exception failure) {
            lost=OraclePlanCollector.transportLost(failure);
            ExecutionStatus status=failure instanceof SQLTimeoutException || failure instanceof com.bitdata.sqlworkbench.connection.ConnectTimeoutException
                ?ExecutionStatus.TIMED_OUT:ExecutionStatus.FAILED;
            String reason=failure instanceof SQLException?"NATIVE_SQL_EXCEPTION":failure.getMessage();
            if(failure instanceof SQLException sql && sql.getSQLState()!=null&&sql.getSQLState().startsWith("08"))reason="CONNECTION_FAILED";
            if(failure instanceof com.bitdata.sqlworkbench.connection.ConnectTimeoutException)reason="CONNECTION_TIMEOUT";
            if(failure instanceof SQLException sql && (sql.getErrorCode()==229||sql.getErrorCode()==262))reason="PLAN_PERMISSION_DENIED";
            if(failure instanceof com.bitdata.sqlworkbench.connection.CredentialFailure credential)reason=credential.code;
            if(failure instanceof com.bitdata.sqlworkbench.connection.WorkloadBusyException)reason="WORKLOAD_BUSY";
            ctx.publish(status,errorResultWithTiming(ctx.executionId,status,"Native plan capture failed",System.currentTimeMillis()-started).withFailureReason(reason));
        } finally {
            if(connection!=null&&!transferred)finishNativeResources(ctx,connection,null,null,null,lost);
            synchronized(ctx) {
                ctx.workerFinished=true;ctx.statement=null; // Keep the shared resource observation after releasing the worker reference.
                if(ctx.cancelWorkers==0&&"CLEANUP_PENDING".equals(ctx.cleanupReason)&&connection==null)ctx.cleanupReason=null;
            }
            if(ticket!=null)ticket.release();
        }
    }

    /** Oracle captures on a dedicated, shared-lease connection; business SELECT is never replayed. */
    private void runOracleAnalyze(ExecutionContext ctx) {
        long started=System.currentTimeMillis();Connection connection=null;boolean transferred=false,lost=false;
        com.bitdata.sqlworkbench.connection.WorkloadLeaseManager.Ticket ticket=null;
        try {
            if("SID".equals(connectionService.getById(ctx.connectionId).orElseThrow().connectionType()))throw new IllegalStateException("CONNECTION_TYPE_UNVERIFIED");
            nativeCheckpoint(ctx);ticket=connectionService.acquireOrdinaryWorkload();synchronized(ctx){ctx.nativeTicket=ticket;}
            var connectionTicket=ticket.retain();ctx.nativeConnectionTicket=connectionTicket;
            connection=connectionService.openOrdinaryWithTimeout(ctx.connectionId,ctx.ownerRevision,
                Math.max(1,TimeUnit.NANOSECONDS.toMillis(ctx.deadlineNanos-System.nanoTime())),connectionTicket);
            nativeCheckpoint(ctx);Connection owned=connection;
            ctx.networkRegistration=networkTimeouts.register(connectionTicket,ctx.networkReason::set);
            nativeMetadata(ctx,()->{owned.setNetworkTimeout(ctx.networkRegistration,(int)Math.max(1,Math.min(15000,TimeUnit.NANOSECONDS.toMillis(ctx.deadlineNanos-System.nanoTime()))));return null;});
            var capabilities=nativeMetadata(ctx,()->PlanCapabilityService.inspect(ctx.connectionId,"oracle",owned,explainServiceFactory,()->nativeCheckpoint(ctx)));
            ctx.capture=new PlanCapture(1,ctx.analyzeMode,null,ctx.sql,null,ctx.connectionId,ctx.sessionId,"oracle",capabilities.serverProduct(),capabilities.serverVersion(),null,capabilities,"NOT_STARTED",List.of());
            if(!capabilities.supports(ctx.analyzeMode))throw new IllegalStateException(capabilities.errorReason()==null?"PLAN_CAPABILITY_UNAVAILABLE":capabilities.errorReason());
            var environment=nativeMetadata(ctx,()->OraclePlanCollector.environment(owned,Math.min(5,nativeTimeout(ctx)),()->nativeCheckpoint(ctx)));
            nativeCheckpoint(ctx);transferred=true;
            var outcome=OraclePlanCollector.capture(owned,ctx.sql,ctx.analyzeMode,()->nativeTimeout(ctx),()->nativeCheckpoint(ctx),
                statement->{synchronized(ctx){ctx.statement=statement;}},
                (resource,statements,transportLost)->finishOracleResources(ctx,resource,statements,transportLost));
            nativeCheckpoint(ctx);var parsed=outcome.parsed();var details=OraclePlanAdapter.details(outcome,environment);var issues=new ArrayList<PlanCapture.Issue>();
            for(String reason:parsed.issues())issues.add(new PlanCapture.Issue(reason,null,"Oracle plan evidence is incomplete"));
            if(!"CONFIRMED".equals(outcome.cleanup()))issues.add(new PlanCapture.Issue(outcome.cleanup(),null,"Client cleanup is unconfirmed"));
            issues.add(new PlanCapture.Issue("ORACLE_METRIC_COMPARABILITY_LIMITED",null,"Native LAST values are separate facts; loops, estimate ratios and node durations are not certified"));
            if("actual".equals(ctx.analyzeMode))issues.add(new PlanCapture.Issue("ORACLE_COLLECTION_OVERHEAD",null,"Session statistics and a unique SQL comment change collection cost and cursor identity"));
            ctx.capture=new PlanCapture(1,ctx.analyzeMode,ctx.analyzeMode,ctx.sql,outcome.commandSql(),ctx.connectionId,ctx.sessionId,"oracle",
                capabilities.serverProduct(),capabilities.serverVersion(),java.time.Instant.now().toString(),capabilities,parsed.parseStatus(),issues,details);
            String reason=parsed.errorReason()!=null?parsed.errorReason():!"CONFIRMED".equals(outcome.cleanup())?outcome.cleanup():environment.errorReason()!=null?environment.errorReason():"ORACLE_METRIC_COMPARABILITY_LIMITED";
            var result=new ExecutionResult(ctx.executionId,ExecutionStatus.COMPLETED,null,null,0,new ExecutionResult.Timing(System.currentTimeMillis()-started),parsed.plan(),parsed.plan()==null?reason:null,null,null,reason,true);
            nativeCheckpoint(ctx);ctx.publish(ExecutionStatus.COMPLETED,result);
        }catch(Exception failure){
            lost=OraclePlanCollector.transportLost(failure);
            ExecutionStatus status=failure instanceof SQLTimeoutException||failure instanceof com.bitdata.sqlworkbench.connection.ConnectTimeoutException?ExecutionStatus.TIMED_OUT:ExecutionStatus.FAILED;
            String reason=failure instanceof SQLException?"ORACLE_PLAN_SQL_REJECTED":failure.getMessage();
            if(failure instanceof SQLException sql){if(lost)reason="CONNECTION_FAILED";else if(Set.of(942,1031).contains(Math.abs(sql.getErrorCode())))reason="PLAN_PERMISSION_DENIED";else if(Math.abs(sql.getErrorCode())==1013)reason="ORACLE_CANCELLED_OR_TIMED_OUT";}
            if(failure instanceof com.bitdata.sqlworkbench.connection.CredentialFailure credential)reason=credential.code;
            if(failure instanceof com.bitdata.sqlworkbench.connection.WorkloadBusyException)reason="WORKLOAD_BUSY";
            ctx.publish(status,errorResultWithTiming(ctx.executionId,status,"Oracle plan capture failed",System.currentTimeMillis()-started).withFailureReason(reason==null?"ORACLE_PLAN_CAPTURE_FAILED":reason));
        }finally{
            if(connection!=null&&!transferred)finishOracleResources(ctx,connection,List.of(),lost);
            synchronized(ctx){ctx.workerFinished=true;ctx.statement=null;if(connection==null&&ctx.cancelWorkers==0&&"CLEANUP_PENDING".equals(ctx.cleanupReason))ctx.cleanupReason=null;}
            if(ticket!=null)ticket.release();
            reconcileNativeNetwork(ctx);
        }
    }
    private void reconcileNativeNetwork(ExecutionContext ctx){
        if(ctx.nativeConnectionTicket!=null&&ctx.nativeConnectionTicket.isReleased()&&!ctx.nativeConnectionTicket.isUnconfirmed()&&ctx.networkRegistration!=null)
            ctx.networkRegistration.connectionCloseConfirmed();
    }
    private String finishOracleResources(ExecutionContext ctx,Connection connection,List<Statement> statements,boolean lost){
        var held=ctx.nativeTicket.retain();var task=new FutureTask<String>(()->{
            ctx.nativeConnectionLock.lock();
            try{
                String state=OraclePlanCollector.close(connection,statements,lost);
                synchronized(ctx){ctx.cleanupReason="CONFIRMED".equals(state)?ctx.cancelWorkers>0?"CANCEL_SIGNAL_PENDING":null:state;}
                reconcileNativeNetwork(ctx);return state;
            }finally{ctx.nativeConnectionLock.unlock();held.release();}
        });
        try{planCleanup.execute(task);}catch(RejectedExecutionException rejected){task.run();ctx.cleanupReason="CLEANUP_EXECUTOR_UNAVAILABLE";return ctx.cleanupReason;}
        try{return task.get(3,TimeUnit.SECONDS);}
        catch(TimeoutException timeout){ctx.cleanupReason="CLEANUP_PENDING";return ctx.cleanupReason;}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();ctx.cleanupReason="CLEANUP_PENDING";return ctx.cleanupReason;}
        catch(ExecutionException failure){ctx.cleanupReason="PLAN_CLEANUP_FAILED";return ctx.cleanupReason;}
    }

    // ─── Result builders ───────────────────────────────────────────────────

    private static ExecutionResult errorResult(String executionId, ExecutionStatus status, String message) {
        return new ExecutionResult(
            executionId, status,
            null, null, 0, null, null, null, message);
    }

    private static ExecutionResult errorResultWithTiming(String executionId, ExecutionStatus status,
                                                         String message, long durationMs) {
        return new ExecutionResult(
            executionId, status,
            null, null, 0, new ExecutionResult.Timing(durationMs), null, null, message);
    }

    /** (B) worker 检查点的取消发布：带阶段原因，且与 cancel() 入口同形（终态 + result）。
     *  三个检查点只走这一个出口，使「CANCELLED 必带 result」由构造保证，而不是靠
     *  每个站点各自记得（旧实现三处都写 (CANCELLED, null)）。 */
    private static void publishCancelled(ExecutionContext ctx, String phase, long startTime) {
        ctx.publish(ExecutionStatus.CANCELLED, errorResultWithTiming(
            ctx.executionId, ExecutionStatus.CANCELLED, phase,
            System.currentTimeMillis() - startTime));
    }

    private static ExecutionResult analyzeErrorResult(String executionId, ExecutionStatus status,
                                                     String message, long durationMs) {
        // Statement-execution failures (timeout / SQLException) carry the message in
        // `error` (9th), mirroring the select path's errorResultWithTiming. planError
        // (8th) stays null here — it is reserved for plan-parse failures on the
        // COMPLETED path (ExecutionEngine.java runAnalyze parse branch).
        return new ExecutionResult(
            executionId, status,
            null, null, 0, new ExecutionResult.Timing(durationMs),
            null, null, message);
    }

    private String lookupDialect(String connectionId) {
        return connectionService.getById(connectionId)
            .map(c -> c.dialect())
            .orElseThrow(() -> new IllegalStateException(
                "Connection not found while looking up dialect: " + connectionId));
    }

    /** One atomic (status, result) publication. Immutable, so a single volatile
     *  write of this reference makes both values visible together — the
     *  "new status + stale result" pairing becomes unrepresentable.
     *
     *  <p>Package-private（原 private）：{@link #isReclaimable(Published)} 的单测要
     *  直接构造 (CANCELLED, null) 这一旧站点形态，才能把 (D) 的语义差异钉成断言。 */
    record Published(ExecutionStatus status, ExecutionResult result) {}

    private enum Kind { EXECUTE, ANALYZE }

    private static class ExecutionContext {
        volatile OracleNetworkTimeoutExecutor.Registration networkRegistration;
        final java.util.concurrent.atomic.AtomicReference<String> networkReason=new java.util.concurrent.atomic.AtomicReference<>();
        long ordinaryObservedRevision;String ordinaryObservedSignature;
        boolean ordinaryOracle,ordinaryStarted;
        volatile boolean exitStarted,exitFinished,exitConnectionOpened,exitConnectionClosed;
        int exitCancelWorkers;volatile String exitReason;
        volatile boolean ordinaryStatementCloseFailed;
        com.bitdata.sqlworkbench.connection.WorkloadLeaseManager.Ticket ordinaryWorkerTicket,ordinaryConnectionTicket;
        String ordinaryDialect,ordinaryRevision;
        volatile String ordinarySignal="NOT_REQUESTED",ordinaryCleanup="NOT_STARTED",ordinaryReason;
        boolean nativePlan;
        final java.util.concurrent.locks.ReentrantLock nativeConnectionLock=new java.util.concurrent.locks.ReentrantLock();
        volatile String nativeSignal="NOT_REQUESTED";long nativeObservedRevision;Object nativeObservedSignature;
        boolean ownerAdmitted;
        com.bitdata.sqlworkbench.connection.ConnectionConfig owner;
        String ownerRevision,sessionInstance;
        long deadlineNanos;
        ScheduledFuture<?> deadlineTask;
        com.bitdata.sqlworkbench.connection.WorkloadLeaseManager.Ticket nativeTicket;
        com.bitdata.sqlworkbench.connection.WorkloadLeaseManager.Ticket nativeConnectionTicket;
        volatile String cleanupReason;
        int cancelWorkers;
        boolean workerFinished;
        ExecutionResult nativeResult;
        PlanObservationReceiptStore observationReceiptStore;
        java.util.function.LongSupplier ticker = System::nanoTime;
        long terminalAt;
        boolean hasTerminalTime;
        String sessionId;
        volatile PlanCapture capture;
        final String executionId;
        final String sql;
        final String connectionId;
        final Kind kind;
        final String analyzeMode;
        /** Effective per-statement timeout (seconds). Either per-request override or default. */
        volatile int effectiveTimeoutSeconds;
        /** Effective row limit. Either per-request override or default. */
        volatile int effectiveMaxRows;
        /** status 与 result 的单一快照，只整体替换、绝不就地改写；写路径与语义见
         *  {@link #publish}。 */
        volatile Published published = new Published(null, null);

        /** status 与 result 的单一发布点。写 = 单次 volatile 写，故读者永远看不到
         *  「新 status + 旧 result」。见 {@link Published}。
         *
         *  <p>(C) 终态闩锁：一旦发布过终态，后续发布一律 no-op（先到终态者胜）。
         *  synchronized 而非 CAS —— 每次执行只有 2~4 次发布，锁争用可忽略，而
         *  锁内读-判-写是最不容易写错的形态；getStatus 走的仍是 volatile 读，
         *  不进入这把锁。
         *
         *  @return {@code true} 表示本次发布生效并赢得闩锁；{@code false} 表示已有
         *          终态先行占据、本次发布被忽略（no-op）。{@code cancel()} 以此如实
         *          回报「取消是否真的生效」。 */
        synchronized boolean publish(ExecutionStatus status, ExecutionResult result) {
            Published current = this.published;
            if (current.status() != null && current.status().isTerminal()) {
                log.debug("publish({}) ignored for execution {}: already terminal ({})",
                    status, executionId, current.status());
                return false;
            }
            if (status != null && status.isTerminal()) { terminalAt = ticker.getAsLong(); hasTerminalTime = true; }
            if(kind==Kind.ANALYZE && result!=null) {
                result=result.withCapture(capture);
                if (status == ExecutionStatus.COMPLETED && result.plan() != null && result.planCapture() != null) {
                    PlanObservationReceiptStore.Result receipt;
                    if (observationReceiptStore == null) receipt = new PlanObservationReceiptStore.Result("FAILED", null, "SNAPSHOT_RECEIPT_STORE_UNAVAILABLE");
                    else receipt = observationReceiptStore.save(result, sessionId, connectionId, capture.dialect());
                    result = result.withObservationReceipt(new ExecutionResult.ObservationReceipt(receipt.status(), receipt.receiptId(), receipt.errorReason()));
                } else if (status != null && status.isTerminal()) {
                    result = result.withObservationReceipt(new ExecutionResult.ObservationReceipt("NOT_APPLICABLE", null,
                            status == ExecutionStatus.COMPLETED ? "PLAN_OBSERVATION_UNAVAILABLE" : "ANALYZE_NOT_COMPLETED"));
                }
            }
            this.published = new Published(status, result);
            return true;
        }
        volatile Future<?> future;
        volatile Statement statement;
        volatile boolean cancelled;

        ExecutionContext(String executionId, String sql, String connectionId, Kind kind, String analyzeMode) {
            this.executionId = executionId;
            this.sql = sql;
            this.connectionId = connectionId;
            this.kind = kind;
            this.analyzeMode = analyzeMode;
        }
    }

    /** Persist before exposing the complete result; cancellation and completion compete on
     * the same latch. A failed history write never changes the successful SELECT outcome. */
    private void completeSelect(ExecutionContext ctx,ExecutionResult result) {
        synchronized(ctx) {
            if(ctx.published.status()!=null && ctx.published.status().isTerminal()) return;
            ExecutionResult.Persistence persistence=new ExecutionResult.Persistence("not_requested",null);
            if(ctx.sessionId!=null && !ctx.sessionId.isBlank()) {
                try {
                    if(historyStore==null) throw new IllegalStateException("History store unavailable");
                    historyStore.save(new com.bitdata.sqlworkbench.history.HistoryEntry(ctx.executionId,ctx.sessionId,ctx.sql,
                        result.completedAt() != null ? result.completedAt() : java.time.Instant.now(),
                        Math.toIntExact(result.timing().durationMs()),result.rowCount(),result.resultCompleteness()));
                    persistence=new ExecutionResult.Persistence("saved",null);
                } catch(Exception failure) { persistence=new ExecutionResult.Persistence("failed","HISTORY_SAVE_FAILED"); }
            }
            ctx.publish(ExecutionStatus.COMPLETED,result.withHistory(persistence));
        }
    }
}
