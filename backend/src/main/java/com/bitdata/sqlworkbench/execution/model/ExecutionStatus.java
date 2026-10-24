package com.bitdata.sqlworkbench.execution.model;

public enum ExecutionStatus {
    QUEUED,
    RUNNING,
    COMPLETED,
    TIMED_OUT,
    FAILED,
    CANCELLED;

    /**
     * 终态：一旦发布就不再被后续发布覆盖（spec 2026-10-01 §4.3 (C)，
     * "先到终态者胜"）。QUEUED / RUNNING 不是终态。
     */
    public boolean isTerminal() {
        return this == COMPLETED || this == TIMED_OUT || this == FAILED || this == CANCELLED;
    }
}
