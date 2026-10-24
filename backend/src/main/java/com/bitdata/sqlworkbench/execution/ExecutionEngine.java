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
    private final java.util.function.LongSupplier ticker;
    private static final long RETENTION_NANOS = TimeUnit.MINUTES.toNanos(5);
    private static final int CAPACITY = 256;
    private final ScheduledExecutorService cleanup = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "execution-retention"); thread.setDaemon(true); return thread;
    });
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private com.bitdata.sqlworkbench.history.HistoryStore historyStore;

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

        // Read the snapshot ONCE into a local. Two reads of `published` could
        // straddle a publish and pair a stale status with a fresh result —
        // that is exactly the gap this fix closes.
        Published p = ctx.published;
        if (p.result() != null) return p.result();

        return new ExecutionResult(
            executionId, p.status(),
            null, null, 0, null, null, null, null
        ).withCapture(ctx.capture);
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
            var p=ctx.published;
            if(p.status()!=ExecutionStatus.COMPLETED || p.result()==null || p.result().plan()==null)
                return new PlanObservation("UNAVAILABLE","PLAN_SOURCE_UNAVAILABLE",null);
            if(p.result().planCapture()==null) return new PlanObservation("UNVERIFIED","UNVERIFIED_PLAN_SOURCE",null);
            return new PlanObservation("VERIFIED",null,p.result());
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
        ctx.ticker = ticker;
        ctx.sessionId=sessionId;
        if(kind==Kind.ANALYZE) ctx.capture=new PlanCapture(1,analyzeMode,null,sql,null,connectionId,sessionId,"unknown",null,null,null,null,"NOT_STARTED",List.of());

        ctx.effectiveTimeoutSeconds = (queryTimeoutSeconds != null && queryTimeoutSeconds > 0)
            ? queryTimeoutSeconds
            : this.queryTimeoutSeconds;
        ctx.effectiveMaxRows = (maxRows != null && maxRows > 0)
            ? Math.min(maxRows, MAX_ROWS_UPPER_BOUND)
            : this.defaultMaxRows;

        ctx.publish(ExecutionStatus.QUEUED, null);
        synchronized (admissionLock) {
            removeExpired();
            if (executions.size() >= CAPACITY) throw new ExecutionCapacityException();
            executions.put(executionId, ctx);
        }

        try {
            Future<?> future = executor.submit(() -> run(ctx));
            ctx.future = future;
        } catch (RejectedExecutionException e) {
            ctx.publish(ExecutionStatus.FAILED, errorResult(executionId,
                ExecutionStatus.FAILED,
                "Execution pool exhausted. Please wait for running queries to complete."));
        }

        return executionId;
    }

    private void run(ExecutionContext ctx) {
        try {
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
            ctx.statement = null;
        }
    }

    /** Terminal eligibility only; removeExpired also requires the full retention window. */
    static boolean isReclaimable(Published published) {
        return published.status() != null && published.status().isTerminal();
    }

    void removeExpired() {
        synchronized (admissionLock) {
            long now = ticker.getAsLong();
            executions.entrySet().removeIf(entry -> {
                ExecutionContext ctx = entry.getValue();
                synchronized (ctx) {
                    return isReclaimable(ctx.published) && ctx.hasTerminalTime && now - ctx.terminalAt >= RETENTION_NANOS;
                }
            });
        }
    }

    @jakarta.annotation.PreDestroy
    public void shutdown() { cleanup.shutdownNow(); executor.shutdownNow(); }

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
            ctx.publish(ExecutionStatus.FAILED, errorResult(ctx.executionId, ExecutionStatus.FAILED, e.getMessage()));
            return false;
        }
    }

    /** Data-mode path: run SELECT, materialize rows. NO EXPLAIN here. */
    private void runSelect(ExecutionContext ctx) {
        ctx.publish(ExecutionStatus.RUNNING, null);
        String dialect = lookupDialect(ctx.connectionId);
        if (!passesSelectGuard(ctx, dialect)) return;
        long startTime = System.currentTimeMillis();

        log.info("Executing SQL on connection {}: {}", ctx.connectionId, ctx.sql);

        try (Connection conn = connectionService.openJdbcConnection(ctx.connectionId)) {
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
                            meta.getColumnName(i),
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
            ctx.publish(resolved, errorResultWithTiming(ctx.executionId, resolved, message,
                System.currentTimeMillis() - startTime));
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
        if(!explainServiceFactory.supports(dialect) || ("mysql".equals(dialect) && "actual".equals(ctx.analyzeMode))) {
            ctx.publish(ExecutionStatus.FAILED,errorResult(ctx.executionId,ExecutionStatus.FAILED,
                "This application does not support the requested plan channel").withCredentialFailure("APPLICATION_CHANNEL_UNSUPPORTED"));
            return;
        }
        long startTime = System.currentTimeMillis();

        log.info("Analyzing SQL on connection {}: {}", ctx.connectionId, ctx.sql);

        try (Connection conn = connectionService.openJdbcConnection(ctx.connectionId)) {
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
                ctx.capture=new PlanCapture(1,ctx.analyzeMode,ctx.analyzeMode,ctx.sql,explainSql,ctx.connectionId,ctx.sessionId,dialect,
                    capabilities.serverProduct(),capabilities.serverVersion(),null,capabilities,"NOT_STARTED",List.of());
                try (ResultSet rs = stmt.executeQuery(explainSql)) {
                    ExecutionResult.PlanNode plan = null;
                    String planError = null;
                    if (rs.next()) {
                        String explainJson = rs.getString(1);
                        ExplainService explainService = explainServiceFactory.get(strategy.dialect());
                        ExplainService.Parsed parsed=explainService.parseDetailed(explainJson,strategy.dialect());
                        plan=parsed.plan();
                        ctx.capture=new PlanCapture(1,ctx.analyzeMode,ctx.analyzeMode,ctx.sql,explainSql,ctx.connectionId,ctx.sessionId,dialect,
                            capabilities.serverProduct(),capabilities.serverVersion(),java.time.Instant.now().toString(),capabilities,parsed.status(),parsed.issues());
                        if (plan == null) {
                            planError = "EXPLAIN output could not be parsed as a plan tree";
                        }
                    } else {
                        planError = "EXPLAIN returned no rows";
                        ctx.capture=new PlanCapture(1,ctx.analyzeMode,ctx.analyzeMode,ctx.sql,explainSql,ctx.connectionId,ctx.sessionId,dialect,
                            capabilities.serverProduct(),capabilities.serverVersion(),null,capabilities,"FAILED",
                            List.of(new PlanCapture.Issue("PLAN_PARSE_FAILED",null,planError)));
                    }

                    long duration = System.currentTimeMillis() - startTime;
                    ctx.publish(ExecutionStatus.COMPLETED, new ExecutionResult(
                        ctx.executionId, ExecutionStatus.COMPLETED,
                        null, null, 0,
                        new ExecutionResult.Timing(duration),
                        plan, planError, null, null,
                        "FAILED".equals(ctx.capture.parseStatus())?"PLAN_PARSE_FAILED":"PARTIAL".equals(ctx.capture.parseStatus())?"PLAN_PARTIAL":null,
                        !"COMPLETE".equals(ctx.capture.parseStatus())
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
            ctx.publish(resolved, analyzeErrorResult(ctx.executionId, resolved, message,
                System.currentTimeMillis() - startTime));
        } catch (Exception e) {
            log.warn("Analyze {} failed (non-SQL): {}", ctx.executionId, e.getMessage(), e);
            ctx.publish(ExecutionStatus.FAILED, analyzeErrorResult(ctx.executionId,
                ExecutionStatus.FAILED,
                "EXPLAIN failed: " + e.getMessage(),
                System.currentTimeMillis() - startTime));
        }
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
            if(kind==Kind.ANALYZE && result!=null) result=result.withCapture(capture);
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
