package com.bitdata.sqlworkbench.diagnostics.trace;

/** Result of a single trace step (one fragment node). */
public record TraceStep(
    int stepIndex,
    String fragmentId,
    String fragmentLabel,
    String sql,             // The EXISTS-wrapped SQL that was executed (for debugging)
    TraceStatus status,
    long rowCount,          // -1 when not queried (EXISTS-only mode)
    long elapsedMs,
    String error,
    String reasonCode,
    java.util.List<String> unavailableKeys,
    String count,
    String fragmentSql,
    String observedAt
) {
    public TraceStep {
        unavailableKeys = unavailableKeys == null ? java.util.List.of() : java.util.List.copyOf(unavailableKeys);
    }
    public TraceStep(int index, String id, String label, String sql, TraceStatus status, long rows, long elapsed, String error,
            String reasonCode, java.util.List<String> unavailableKeys) {
        this(index,id,label,sql,status,rows,elapsed,error,reasonCode,unavailableKeys,null,null,null);
    }
    public TraceStep withObservation(String fragmentSql, String count) {
        return new TraceStep(stepIndex,fragmentId,fragmentLabel,sql,status,rowCount,elapsedMs,error,reasonCode,unavailableKeys,
            count,fragmentSql,java.time.Instant.now().toString());
    }
    public TraceStep(int index, String id, String label, String sql, TraceStatus status, long rows, long elapsed, String error) {
        this(index, id, label, sql, status, rows, elapsed, error,
            status == TraceStatus.ERROR ? "STEP_ERROR" : status == TraceStatus.TIMEOUT ? "STEP_TIMEOUT" :
                status == TraceStatus.SKIPPED ? "NOT_EXECUTABLE" : null, java.util.List.of());
    }
}
