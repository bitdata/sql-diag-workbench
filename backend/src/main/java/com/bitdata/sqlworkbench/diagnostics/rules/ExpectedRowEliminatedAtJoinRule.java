package com.bitdata.sqlworkbench.diagnostics.rules;

import com.bitdata.sqlworkbench.diagnostics.DiagnosticFinding;
import com.bitdata.sqlworkbench.diagnostics.DiagnosticRule;
import com.bitdata.sqlworkbench.diagnostics.trace.EliminationEdge;
import com.bitdata.sqlworkbench.diagnostics.trace.TraceContext;
import com.bitdata.sqlworkbench.diagnostics.trace.TraceStep;
import com.bitdata.sqlworkbench.execution.model.ExecutionResult.PlanNode;
import com.bitdata.sqlworkbench.execution.model.ExecutionResult.Timing;
import com.bitdata.sqlworkbench.sql.model.FragmentNode;
import com.bitdata.sqlworkbench.sql.model.JoinInfo;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * When row trace shows a fragment has rows but its parent JOIN produces fewer rows,
 * the JOIN condition eliminated rows.
 */
@Component
public class ExpectedRowEliminatedAtJoinRule implements DiagnosticRule {

    @Override
    public String id() {
        return "expected-row-eliminated-at-join";
    }

    @Override
    public String displayName() {
        return "Row Elimination at JOIN";
    }

    @Override
    public String description() {
        return "When a fragment has rows but its parent JOIN produces fewer rows, the JOIN condition eliminated them.";
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
        if (edge == null || edge.addedJoins().isEmpty()) return List.of();

        TraceStep parent = edge.parentStep();
        String joins = edge.addedJoins().stream()
            .map(j -> j.tableName()
                + (j.tableAlias() != null && !j.tableAlias().isBlank()
                    ? " AS " + j.tableAlias() : ""))
            .reduce((a, b) -> a + ", " + b)
            .orElse("");

        return List.of(DiagnosticFinding.builder()
            .id(id() + "-" + parent.fragmentId())
            .ruleId(id())
            .severity(Severity.MEDIUM)
            .title("Observed key difference near JOIN: " + parent.fragmentLabel())
            .message("The child query found the key; "
                + parent.fragmentLabel() + " did not. Added JOIN(s): " + joins
                + ". Join conditions or unmatched related rows are candidate explanations, not a verified unique cause.")
            .evidence(List.of(
                "Elimination point: " + parent.fragmentLabel(),
                "Added joins: " + joins,
                "Keys: " + formatKeys(context.keys())
            ))
            .confidence(0.9)
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
