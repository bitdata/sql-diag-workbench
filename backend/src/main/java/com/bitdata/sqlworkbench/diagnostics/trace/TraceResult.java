package com.bitdata.sqlworkbench.diagnostics.trace;

import com.bitdata.sqlworkbench.diagnostics.DiagnosticFinding;
import java.util.List;

/** Complete output of a finished trace. */
public record TraceResult(
    List<TraceStep> steps,
    TraceStep eliminationStep,      // null = row not eliminated
    long totalElapsedMs,
    String error,                    // null = success
    List<DiagnosticFinding> findings, // from trace-based diagnostic rules
    EliminationOutcome eliminationOutcome, // sealed detector outcome (Edge/Error/Missing/None)
    EliminationEdge eliminationEdge,       // enriched edge with budget diff; null unless Edge
    String errorReason,
    TraceEvidence evidence
) {
    public TraceResult(List<TraceStep> steps, TraceStep elimination, long elapsed, String error,
                       List<DiagnosticFinding> findings, EliminationOutcome outcome, EliminationEdge edge, String reason) {
        this(steps, elimination, elapsed, error, findings, outcome, edge, reason, null);
    }
}
