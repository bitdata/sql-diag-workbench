package com.bitdata.sqlworkbench.diagnostics.trace;

import java.util.*;
import com.bitdata.sqlworkbench.diagnostics.DiagnosticFinding;

/** Observations cover a submitted exploration tree, never unobserved physical sources. */
public record TraceEvidence(String scope, String coverage, int totalNodes, int observedNodes,
                            String rootPresence, List<TraceLimitation> limitations) {
    public static TraceEvidence summarize(String rootId, int total, List<TraceStep> steps,
                                          List<DiagnosticFinding> findings, List<TraceLimitation> extra) {
        int observed = 0;
        String presence = "UNKNOWN";
        List<TraceLimitation> limitations = new ArrayList<>(extra);
        for (TraceStep step : steps) {
            boolean success = step.status() == TraceStatus.HAS_ROWS || step.status() == TraceStatus.NO_ROWS;
            if (success) observed++;
            if (Objects.equals(rootId, step.fragmentId()) && success)
                presence = step.status() == TraceStatus.HAS_ROWS ? "PRESENT" : "ABSENT";
            if (!success) limitations.add(new TraceLimitation(
                step.reasonCode() != null ? step.reasonCode() : "STEP_ERROR", step.fragmentId(), null,
                step.error() != null ? step.error() : step.status().name()));
        }
        if (presence.equals("UNKNOWN")) limitations.add(new TraceLimitation("ROOT_UNOBSERVED", rootId, null,
            "The root query has not been successfully observed."));
        for (DiagnosticFinding f : findings) if (f.degraded())
            limitations.add(new TraceLimitation(f.errorReason(), f.affectedNodeId(), f.ruleId(), f.message()));
        return new TraceEvidence("CURRENT_TREE", total > 0 && observed == total ? "ALL_OBSERVED" : "PARTIAL",
            total, observed, presence, List.copyOf(limitations));
    }
}
