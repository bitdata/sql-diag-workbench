package com.bitdata.sqlworkbench.diagnostics.trace;

/** Status of a single trace step or overall trace. */
public enum TraceStatus {
    // Step-level statuses
    HAS_ROWS,   // EXISTS returned true
    NO_ROWS,    // EXISTS returned false
    SKIPPED,    // Non-executable fragment, inferred from children
    ERROR,      // JDBC or SQL error on this step
    TIMEOUT,    // Per-step query timeout

    // Trace-level statuses (used in TraceProgress.status)
    QUEUED,     // Submitted but not yet running
    RUNNING,    // Currently executing steps
    COMPLETED,  // All steps done
    CANCELLED,  // User cancelled
    FAILED;     // Unrecoverable error

    /**
     * 终态：一旦发布就不再被后续发布覆盖（先到终态者胜）。镜像渲染层的
     * {@code useRowTrace.ts} 的 {@code TERMINAL_STATUSES} —— 两处必须一致，
     * 否则后端会停在「已终态」而前端继续轮询，或反之。
     *
     * <p>步级取值（HAS_ROWS / NO_ROWS / SKIPPED / ERROR / TIMEOUT）从不作为
     * 整条 trace 的 {@link TraceProgress#status()}，故不在此集合内。
     */
    public boolean isTerminal() {
        return this == COMPLETED || this == CANCELLED || this == FAILED;
    }
}
