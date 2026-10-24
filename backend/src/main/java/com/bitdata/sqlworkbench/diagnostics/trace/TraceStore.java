package com.bitdata.sqlworkbench.diagnostics.trace;

import com.bitdata.sqlworkbench.diagnostics.DiagnosticFinding;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import java.sql.Statement;
import java.util.*;
import java.util.concurrent.*;

/**
 * In-memory store for async trace operations.
 * Follows the same pattern as ExecutionEngine's internal state map.
 * Auto-cleans completed traces after 5 minutes.
 */
@Component
public class TraceStore {

    private static final Logger log = LoggerFactory.getLogger(TraceStore.class);
    private static final long CLEANUP_DELAY_MS = 5 * 60 * 1000;

    private final Map<String, MutableTraceState> traces = new ConcurrentHashMap<>();
    private final java.util.function.LongSupplier ticker;
    // Driver cancellation may block indefinitely; it must never occupy the budget scheduler.
    private final ExecutorService budgetCancellation = new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(64), r -> {
            Thread t = new Thread(r, "trace-budget-cancel"); t.setDaemon(true); return t;
        });
    private final ScheduledExecutorService cleanup = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "trace-cleanup");
        t.setDaemon(true);
        return t;
    });

    public TraceStore() {
        this(System::nanoTime);
    }

    TraceStore(java.util.function.LongSupplier ticker) {
        this.ticker=ticker;
        cleanup.scheduleAtFixedRate(this::removeExpired, 60, 60, TimeUnit.SECONDS);
    }

    @PreDestroy
    public void shutdown() {
        traces.values().forEach(state -> { synchronized(state) { if(state.budgetFuture!=null) state.budgetFuture.cancel(false); } });
        cleanup.shutdownNow();
        budgetCancellation.shutdownNow();
    }

    public String create(TraceRequest request) {
        String traceId = UUID.randomUUID().toString();
        MutableTraceState state=new MutableTraceState(traceId, request, ticker.getAsLong());
        traces.put(traceId, state);
        if(request.mode()==TraceMode.CARDINALITY) synchronized(state) {
            state.budgetFuture=cleanup.schedule(() -> expireBudget(traceId),120,TimeUnit.SECONDS);
        }
        return traceId;
    }

    public int remainingTimeout(String traceId, int requested) {
        MutableTraceState state=traces.get(traceId);
        if(state==null) return 1;
        if(state.request.mode()!=TraceMode.CARDINALITY) return Math.max(1,requested);
        long remaining=TimeUnit.SECONDS.toNanos(120)-(ticker.getAsLong()-state.startedNanos);
        if(remaining<=0) {
            expireBudget(traceId);
            return 1;
        }
        return Math.min(Math.max(1,requested),(int)Math.max(1,(remaining+999_999_999L)/1_000_000_000L));
    }

    public boolean completeQuantity(String traceId, List<QuantityEvidence.Comparison> comparisons, List<TraceLimitation> limitations) {
        MutableTraceState state=traces.get(traceId);
        return state!=null && state.publish(TraceStatus.COMPLETED,() -> { state.comparisons=List.copyOf(comparisons); state.extra.addAll(limitations); });
    }

    public MutableTraceState get(String traceId) {
        return traces.get(traceId);
    }

    public void setRunning(String traceId) {
        MutableTraceState state = traces.get(traceId);
        if (state != null) state.publish(TraceStatus.RUNNING, null);
    }

    public void addStep(String traceId, TraceStep step) {
        MutableTraceState state = traces.get(traceId);
        if (state != null) {
            synchronized (state) {
                if (!state.status.isTerminal()) state.completedSteps.add(step);
            }
        }
    }

    public void setActiveStatement(String traceId, Statement stmt) {
        registerActiveStatement(traceId, stmt);
    }

    public boolean registerActiveStatement(String traceId, Statement stmt) {
        MutableTraceState state = traces.get(traceId);
        if (state == null) return false;
        synchronized (state) {
            if (state.status.isTerminal() || state.cancelled) return false;
            state.activeStatement = stmt;
            return true;
        }
    }

    public void clearActiveStatement(String traceId) {
        MutableTraceState state = traces.get(traceId);
        if (state != null) synchronized (state) { state.activeStatement = null; }
    }

    public boolean canExecute(String traceId) {
        MutableTraceState state = traces.get(traceId);
        if (state == null) return false;
        synchronized (state) { return !state.status.isTerminal() && !state.cancelled; }
    }

    public void configureScope(String traceId, String rootId, int total) {
        MutableTraceState state = traces.get(traceId);
        if (state != null) synchronized (state) {
            if (!state.status.isTerminal()) { state.rootId = rootId; state.totalNodes = total; }
        }
    }

    /** Publish the COMPLETED terminal state. Returns whether this publish won the
     *  terminal latch; {@code false} means a terminal state already stood and the
     *  existing one is left untouched. */
    public boolean complete(
        String traceId,
        TraceStep eliminationStep,
        List<DiagnosticFinding> findings,
        EliminationOutcome outcome,
        String errorReason
    ) {
        return complete(traceId, eliminationStep, findings, outcome, errorReason, List.of());
    }

    public boolean complete(String traceId, TraceStep eliminationStep, List<DiagnosticFinding> findings,
                            EliminationOutcome outcome, String errorReason, List<TraceLimitation> extra) {
        MutableTraceState state = traces.get(traceId);
        if (state == null) return false;
        return state.publish(TraceStatus.COMPLETED, () -> {
            state.eliminationStep = eliminationStep;
            state.findings = findings != null ? findings : List.of();
            state.eliminationOutcome = outcome;
            state.errorReason = errorReason;
            state.extra.addAll(extra);
        });
    }

    /** Publish the FAILED terminal state. Returns whether this publish won the
     *  terminal latch (see {@link #complete}). */
    public boolean fail(String traceId, String error) {
        return fail(traceId,error,null);
    }
    public boolean fail(String traceId,String error,String reason) {
        MutableTraceState state = traces.get(traceId);
        if (state == null) return false;
        return state.publish(TraceStatus.FAILED, () -> { state.error = error; state.errorReason=reason; });
    }

    /**
     * 请求取消一条 trace。
     *
     * <p>镜像 {@code ExecutionEngine.cancel}：返回 {@code true} 当且仅当这次
     * 调用发布的 CANCELLED **赢得了终态闩锁**。{@code false} 表示本次取消**未
     * 生效**：id 不存在、调用时已是终态、或 worker 在下面的判定与发布之间先
     * 赢了闩锁（例如它已 COMPLETED）。HTTP 层（{@code DiagnosticsController.
     * cancelTrace}）据此回 404——语义是「没有生效」而非「失败」。
     */
    public boolean cancel(String traceId) {
        return stop(traceId,TraceStatus.CANCELLED,"Cancelled by user request",null);
    }

    private boolean stop(String traceId, TraceStatus terminal, String message, String reason) {
        return stop(traceId, terminal, message, reason, false);
    }

    void expireBudget(String traceId) {
        stop(traceId,TraceStatus.FAILED,"Trace application budget exceeded","TRACE_BUDGET_EXCEEDED",true);
    }

    private boolean stop(String traceId, TraceStatus terminal, String message, String reason, boolean asynchronousSignal) {
        MutableTraceState state = traces.get(traceId);
        if (state == null) return false;
        Statement active;
        synchronized (state) {
            if (state.status.isTerminal()) return false;
            state.cancelled = true;
            active=state.activeStatement;
            state.cancelSignal=new CancelSignal(active==null?"NOT_REQUIRED":"PENDING",null);
            state.publish(terminal,() -> { state.error=message; state.errorReason=reason; });
        }
        // Never hold the state lock across a driver cancellation call. Only this receipt can change after terminal.
        if(active!=null) {
            Runnable signal = () -> {
            try {
                active.cancel();
                synchronized(state) { state.cancelSignal=new CancelSignal("SENT",null); }
            } catch (Exception e) {
                log.warn("Failed to cancel trace statement: {}", e.getMessage());
                synchronized(state) { state.cancelSignal=new CancelSignal("FAILED","CANCEL_SIGNAL_FAILED"); }
            }
            };
            if (asynchronousSignal) {
                try { budgetCancellation.execute(signal); }
                catch (RejectedExecutionException e) {
                    synchronized(state) { state.cancelSignal=new CancelSignal("FAILED","CANCEL_SIGNAL_REJECTED"); }
                }
            } else signal.run();
        }
        return true;
    }

    public TraceProgress toProgress(String traceId) {
        MutableTraceState state = traces.get(traceId);
        if (state == null) return null;
        synchronized (state) {
        long elapsed = state.startedAt > 0
            ? (state.status.isTerminal() ? state.completedAt : System.currentTimeMillis()) - state.startedAt
            : 0;

        List<TraceStep> stepsSnapshot;
        synchronized (state.completedSteps) {
            stepsSnapshot = new ArrayList<>(state.completedSteps);
        }

        return new TraceProgress(
            traceId,
            state.status,
            List.copyOf(stepsSnapshot),
            state.eliminationStep,
            state.findings != null ? List.copyOf(state.findings) : List.of(),
            elapsed,
            state.error,
            state.eliminationOutcome,
            state.errorReason,
            state.evidence,
            state.request.mode(),
            state.request.mode()==TraceMode.CARDINALITY ? QuantityEvidence.summarize(state.request,state.rootId,state.status,stepsSnapshot,state.comparisons) : null,
            state.cancelSignal
        );
        }
    }

    private void removeExpired() {
        long now = System.currentTimeMillis();
        traces.entrySet().removeIf(entry -> {
            MutableTraceState state = entry.getValue();
            return state.completedAt > 0 && (now - state.completedAt) > CLEANUP_DELAY_MS;
        });
    }

    /** Mutable internal state for a single trace operation. */
    static class MutableTraceState {
        final String traceId;
        final TraceRequest request;
        final long startedAt;
        final long startedNanos;
        ScheduledFuture<?> budgetFuture;
        CancelSignal cancelSignal;
        List<QuantityEvidence.Comparison> comparisons=List.of();
        final List<TraceStep> completedSteps = new ArrayList<>();

        volatile TraceStatus status = TraceStatus.QUEUED;
        volatile TraceStep eliminationStep;
        volatile EliminationOutcome eliminationOutcome;  // added 2026-08-27
        volatile String errorReason;                      // added 2026-08-27; null unless outcome is Error
        volatile List<DiagnosticFinding> findings;
        volatile String error;
        volatile Statement activeStatement;
        volatile boolean cancelled;
        volatile long completedAt;
        String rootId;
        int totalNodes;
        TraceEvidence evidence;
        final List<TraceLimitation> extra = new ArrayList<>();

        MutableTraceState(String traceId, TraceRequest request, long startedNanos) {
            this.traceId = traceId;
            this.request = request;
            this.startedAt = System.currentTimeMillis();
            this.startedNanos = startedNanos;
        }

        /**
         * 状态发布的唯一入口，语义对齐 {@code ExecutionEngine.ExecutionContext.
         * publish}：终态闩锁（先到终态者胜）+ 返回值告诉调用方本次是否生效。
         * 非终态发布（QUEUED→RUNNING）照常推进——闩锁只锁终态。
         *
         * <p>{@code payload}（终态携带的字段，如取消原因）在 status **之前**
         * 写入，故 volatile 读到的终态必然连带其 payload 可见——不会出现「终态
         * 但原因缺失」的撕裂读。丢失闩锁时 payload 不执行，既有终态连同其
         * payload 原样不动。
         *
         * <p>synchronized 而非 CAS：每条 trace 只有 2~4 次发布，锁争用可忽略，
         * 锁内读-判-写最不容易写错；读取方（{@link #toProgress}）仍走 volatile 读。
         *
         * @return {@code true} 表示本次发布生效并赢得闩锁；{@code false} 表示已
         *         有终态先行占据、本次发布被忽略（no-op）。
         */
        synchronized boolean publish(TraceStatus next, Runnable payload) {
            if (status.isTerminal()) {
                return false;
            }
            if (payload != null) {
                payload.run();
            }
            if (next.isTerminal()) {
                if(budgetFuture!=null) budgetFuture.cancel(false);
                if (next == TraceStatus.CANCELLED || next == TraceStatus.FAILED)
                    extra.add(new TraceLimitation(next == TraceStatus.CANCELLED ? "CANCELLED" : "TRACE_FAILED", null, null,
                        error != null ? error : next.name()));
                evidence = TraceEvidence.summarize(rootId, totalNodes, List.copyOf(completedSteps),
                    findings != null ? findings : List.of(), extra);
                completedAt = System.currentTimeMillis();
            }
            status = next;
            return true;
        }
    }
}
