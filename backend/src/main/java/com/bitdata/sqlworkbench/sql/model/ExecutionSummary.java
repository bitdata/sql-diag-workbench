package com.bitdata.sqlworkbench.sql.model;

/**
 * Per-node execution metrics. Populated after a successful SQL execution
 * and used by LineagePanel to show row counts and duration (spec §4.2).
 */
public record ExecutionSummary(
    long rows,
    long durationMs,
    com.bitdata.sqlworkbench.execution.model.ResultCompleteness resultCompleteness
) {
    public ExecutionSummary {
        resultCompleteness = com.bitdata.sqlworkbench.execution.model.ResultCompleteness.normalize(resultCompleteness);
    }
    public ExecutionSummary(long rows, long durationMs) { this(rows, durationMs, null); }
}
