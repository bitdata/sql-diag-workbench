package com.bitdata.sqlworkbench.diagnostics.rules;

import com.bitdata.sqlworkbench.diagnostics.DiagnosticFinding;
import com.bitdata.sqlworkbench.diagnostics.DiagnosticRule;
import com.bitdata.sqlworkbench.execution.model.ExecutionResult.PlanNode;
import com.bitdata.sqlworkbench.execution.model.ExecutionResult.Timing;
import com.bitdata.sqlworkbench.sql.model.FragmentNode;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Detects when PostgreSQL's row estimate (Plan Rows) differs significantly from actual rows (Actual Rows).
 * A large mismatch (e.g., estimate 10x higher or lower) suggests stale statistics.
 */
@Component
public class EstimatedVsActualRule implements DiagnosticRule {

    private static final double MISMATCH_THRESHOLD = 10.0;

    @Override
    public String id() {
        return "estimated-vs-actual";
    }

    @Override
    public String displayName() {
        return "Estimated vs Actual Rows";
    }

    @Override
    public String description() {
        return "Detects when row estimates differ significantly from actual row counts, without inferring a cause.";
    }

    @Override
    public Severity severity() {
        return Severity.MEDIUM;
    }

    @Override
    public List<DiagnosticFinding> analyze(FragmentNode fragment, PlanNode plan, Timing timing) {
        if(plan==null || fragment==null) return List.of();
        var findings=new java.util.ArrayList<DiagnosticFinding>();
        com.bitdata.sqlworkbench.diagnostics.PlanNodeWalker.walk(plan,node -> {
            var metrics=node.metrics();
            if(metrics==null || !"DATABASE_NODE".equals(metrics.origin()) || !metrics.estimatedRows().available() || !metrics.actualRows().available()
                || !metrics.loops().available() || metrics.loops().decimal().signum()==0) return;
            var estimate=metrics.estimatedRows().decimal(); var actual=metrics.actualRows().decimal();
            boolean mismatch=estimate.signum()==0 ? actual.signum()>0 : actual.signum()==0
                || actual.compareTo(estimate.multiply(java.math.BigDecimal.TEN))>0
                || estimate.compareTo(actual.multiply(java.math.BigDecimal.TEN))>0;
            if(!mismatch) return;
            findings.add(DiagnosticFinding.builder().id(id()+"-"+fragment.id()+"-"+findings.size()).ruleId(id()).severity(Severity.MEDIUM)
                .title("Row estimate mismatch: "+actual.toPlainString()+" actual vs "+estimate.toPlainString()+" estimated")
                .message("Observed per-loop output differs from the per-invocation estimate. This does not establish stale statistics or a cause.")
                .evidence(List.of("Operation: "+node.operation(),"Source: "+metrics.sourcePath(),"Loops: "+metrics.loops().value()))
                .confidence(0.8).affectedNodeId(fragment.id()).build());
        });
        return findings;
    }
}
