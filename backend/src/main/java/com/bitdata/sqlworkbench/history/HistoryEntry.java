package com.bitdata.sqlworkbench.history;

import java.time.Instant;
import com.bitdata.sqlworkbench.execution.model.ResultCompleteness;

public record HistoryEntry(
    String id,
    String sessionId,
    String sqlText,
    Instant executedAt,
    Integer durationMs,
    Integer rowCount,
    ResultCompleteness resultCompleteness
) {
    public HistoryEntry { resultCompleteness = ResultCompleteness.normalize(resultCompleteness); }
    public HistoryEntry(String id, String sessionId, String sqlText, Instant executedAt, Integer durationMs, Integer rowCount) {
        this(id, sessionId, sqlText, executedAt, durationMs, rowCount, null);
    }
}
