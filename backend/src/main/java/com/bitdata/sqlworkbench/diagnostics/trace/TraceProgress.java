package com.bitdata.sqlworkbench.diagnostics.trace;

import com.bitdata.sqlworkbench.diagnostics.DiagnosticFinding;
import java.util.List;

/**
 * Polling response for an in-flight or completed trace.
 *
 * <p>{@code eliminationOutcome} and {@code errorReason} are added in 2026-08-27
 * to make the sealed outcome a first-class citizen of the polling payload.
 * {@code eliminationOutcome} may be null on pre-2026-08-27 backend responses;
 * the frontend must fall back to {@code eliminationStep} in that case.
 */
public record TraceProgress(
    String traceId,
    TraceStatus status,
    List<TraceStep> steps,
    TraceStep eliminationStep,
    List<DiagnosticFinding> findings,
    long totalElapsedMs,
    String error,
    EliminationOutcome eliminationOutcome,
    String errorReason,
    TraceEvidence evidence,
    TraceMode mode,
    QuantityEvidence quantity,
    CancelSignal cancelSignal
) {
    public TraceProgress(String id, TraceStatus status, List<TraceStep> steps, TraceStep elimination,
                         List<DiagnosticFinding> findings, long elapsed, String error, EliminationOutcome outcome, String reason, TraceEvidence evidence) {
        this(id,status,steps,elimination,findings,elapsed,error,outcome,reason,evidence,TraceMode.EXISTENCE,null,null);
    }
    public TraceProgress(String id, TraceStatus status, List<TraceStep> steps, TraceStep elimination,
                         List<DiagnosticFinding> findings, long elapsed, String error, EliminationOutcome outcome, String reason) {
        this(id, status, steps, elimination, findings, elapsed, error, outcome, reason, null);
    }
}
