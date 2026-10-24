package com.bitdata.sqlworkbench.diagnostics.rules;

import com.bitdata.sqlworkbench.diagnostics.DiagnosticFinding;
import com.bitdata.sqlworkbench.diagnostics.DiagnosticRule;
import com.bitdata.sqlworkbench.diagnostics.trace.EliminationEdge;
import com.bitdata.sqlworkbench.diagnostics.trace.TraceContext;
import com.bitdata.sqlworkbench.diagnostics.trace.TraceStep;
import com.bitdata.sqlworkbench.execution.model.ExecutionResult.PlanNode;
import com.bitdata.sqlworkbench.execution.model.ExecutionResult.Timing;
import com.bitdata.sqlworkbench.sql.model.FragmentNode;
import com.bitdata.sqlworkbench.sql.model.WhereInfo;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/**
 * When row trace shows a fragment has rows but a parent FILTER/WHERE eliminates rows,
 * the predicate is too restrictive.
 */
@Component
public class ExpectedRowEliminatedAtFilterRule implements DiagnosticRule {

    private static final Pattern FILTER_PATTERN = Pattern.compile(
        "Filter|WHERE|Having|qualify", Pattern.CASE_INSENSITIVE);

    @Override
    public String id() {
        return "expected-row-eliminated-at-filter";
    }

    @Override
    public String displayName() {
        return "Row Elimination at Filter";
    }

    @Override
    public String description() {
        return "When a fragment has rows but a parent filter eliminates rows, the predicate may be too restrictive.";
    }

    @Override
    public Severity severity() {
        return Severity.MEDIUM;
    }

    @Override
    public List<DiagnosticFinding> analyze(FragmentNode fragment, PlanNode plan, Timing timing) {
        // Plan aggregate counts cannot establish record-level loss or join causality.
        // RuleRegistry reports the explicit missing-evidence reason. Trace evidence remains independent.
        return List.of();
    }

    @Override
    public List<DiagnosticFinding> analyzeTrace(TraceContext context) {
        EliminationEdge edge = context.traceResult().eliminationEdge();
        if (edge == null || edge.addedWhere().isEmpty()) return List.of();

        TraceStep parent = edge.parentStep();
        String predicates = edge.addedWhere().stream()
            .map(WhereInfo::conditionSql)
            .reduce((a, b) -> a + " AND " + b)
            .orElse("");

        return List.of(DiagnosticFinding.builder()
            .id(id() + "-" + parent.fragmentId())
            .ruleId(id())
            .severity(Severity.MEDIUM)
            .title("Observed key difference near filter: " + parent.fragmentLabel())
            .message("The child query found the key; " + parent.fragmentLabel()
                + " did not. Added predicate(s): " + predicates
                + ". These predicates are candidate explanations, not a verified unique cause.")
            .evidence(List.of(
                "Elimination point: " + parent.fragmentLabel(),
                "Added predicates: " + predicates,
                "Keys: " + formatKeys(context.keys())
            ))
            .confidence(0.85)
            .affectedNodeId(parent.fragmentId())
            .build());
    }

    private String formatKeys(java.util.List<com.bitdata.sqlworkbench.diagnostics.trace.KeyValue> keys) {
        return keys.stream()
            .map(kv -> kv.column() + " = " + kv.value())
            .reduce((a, b) -> a + ", " + b)
            .orElse("");
    }

}
