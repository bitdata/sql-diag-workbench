package com.bitdata.sqlworkbench.diagnostics;

import com.bitdata.sqlworkbench.execution.model.ExecutionResult.PlanNode;
import com.bitdata.sqlworkbench.execution.model.ExecutionResult.Timing;
import com.bitdata.sqlworkbench.sql.model.FragmentNode;

import java.util.List;

/**
 * A diagnostic rule that analyzes a fragment's execution plan and
 * returns one or more findings about potential performance or correctness issues.
 */
public interface DiagnosticRule {

    /** Unique rule identifier, e.g. "estimated-vs-actual". */
    String id();

    /** Human-readable display name, e.g. "Estimated vs Actual Rows". */
    String displayName();

    /** One-line description shown as tooltip in the UI. */
    String description();

    /** Severity level of findings produced by this rule. */
    Severity severity();

    /**
     * Analyze a fragment's execution plan and return findings.
     *
     * @param fragment the fragment being analyzed
     * @param plan     the EXPLAIN plan (may be null)
     * @param timing   execution timing info (may be null)
     * @return list of findings; empty if rule does not apply
     */
    List<DiagnosticFinding> analyze(FragmentNode fragment, PlanNode plan, Timing timing);

    /**
     * Analyze trace results and return findings.
     * Default empty implementation — only trace-aware rules override this.
     */
    default List<DiagnosticFinding> analyzeTrace(
            com.bitdata.sqlworkbench.diagnostics.trace.TraceContext context) {
        return List.of();
    }

    enum Severity {
        HIGH,   // High-impact issue that likely explains wrong/empty results
        MEDIUM, // Moderate issue that may cause performance problems
        LOW     // Low-impact observation or optimization hint
    }
}