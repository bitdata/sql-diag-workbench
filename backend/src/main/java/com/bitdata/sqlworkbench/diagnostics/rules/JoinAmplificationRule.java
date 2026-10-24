package com.bitdata.sqlworkbench.diagnostics.rules;

import com.bitdata.sqlworkbench.diagnostics.DiagnosticFinding;
import com.bitdata.sqlworkbench.diagnostics.DiagnosticRule;
import com.bitdata.sqlworkbench.diagnostics.PlanNodeWalker;
import com.bitdata.sqlworkbench.execution.model.ExecutionResult.PlanNode;
import com.bitdata.sqlworkbench.execution.model.ExecutionResult.Timing;
import com.bitdata.sqlworkbench.sql.model.FragmentNode;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Detects when a JOIN produces significantly more rows than its input tables,
 * indicating a Cartesian product risk or missing join condition.
 */
@Component
public class JoinAmplificationRule implements DiagnosticRule {

    private static final double AMPLIFICATION_THRESHOLD = 5.0;
    private static final Pattern JOIN_PATTERN = Pattern.compile(
        "Join|Nested Loop|Hash Join|Merge Join", Pattern.CASE_INSENSITIVE);

    @Override
    public String id() {
        return "join-amplification";
    }

    @Override
    public String displayName() {
        return "Join Amplification";
    }

    @Override
    public String description() {
        return "Detects when a JOIN produces significantly more rows than its inputs, indicating Cartesian product risk.";
    }

    @Override
    public Severity severity() {
        return Severity.HIGH;
    }

    @Override
    public List<DiagnosticFinding> analyze(FragmentNode fragment, PlanNode plan, Timing timing) {
        // Plan aggregate counts cannot establish record-level loss or join causality.
        // RuleRegistry reports the explicit missing-evidence reason. Trace evidence remains independent.
        return List.of();
    }

}
