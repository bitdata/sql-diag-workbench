package com.bitdata.sqlworkbench.diagnostics.rules;

import com.bitdata.sqlworkbench.diagnostics.DiagnosticFinding;
import com.bitdata.sqlworkbench.diagnostics.DiagnosticRule;
import com.bitdata.sqlworkbench.diagnostics.trace.TraceContext;
import com.bitdata.sqlworkbench.execution.model.ExecutionResult.PlanNode;
import com.bitdata.sqlworkbench.execution.model.ExecutionResult.Timing;
import com.bitdata.sqlworkbench.sql.model.FragmentNode;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Compatibility registration only. Exploration leaves cannot establish source absence.
 */
@Component
public class ExpectedRowMissingAtSourceRule implements DiagnosticRule {

    
    
    @Override
    public String id() {
        return "expected-row-missing-at-source";
    }

    @Override
    public String displayName() {
        return "Missing Row at Source";
    }

    @Override
    public String description() {
        return "Source verification is unavailable from the current exploration tree.";
    }

    @Override
    public Severity severity() {
        return Severity.HIGH;
    }

    @Override
    public List<DiagnosticFinding> analyze(FragmentNode fragment, PlanNode plan, Timing timing) {
        // This rule is triggered by the row trace endpoint, not the analyze endpoint.
        return List.of();
    }

    @Override
    public List<DiagnosticFinding> analyzeTrace(TraceContext context) {
        // Legacy outcome payloads do not prove physical source absence.
        return List.of();
    }
}
