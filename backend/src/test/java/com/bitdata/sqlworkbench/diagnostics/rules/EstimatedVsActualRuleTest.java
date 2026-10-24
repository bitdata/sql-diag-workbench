package com.bitdata.sqlworkbench.diagnostics.rules;

import com.bitdata.sqlworkbench.diagnostics.DiagnosticFinding;
import com.bitdata.sqlworkbench.diagnostics.DiagnosticRule;
import com.bitdata.sqlworkbench.execution.model.ExecutionResult.PlanNode;
import com.bitdata.sqlworkbench.sql.model.FragmentNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.bitdata.sqlworkbench.diagnostics.rules.DiagnosticRuleTestFixture.*;
import static org.junit.jupiter.api.Assertions.*;

class EstimatedVsActualRuleTest {

    private final EstimatedVsActualRule rule = new EstimatedVsActualRule();

    @Test
    void overestimate_whenActualMuchLessThanPlan() {
        // Plan estimated 1000 rows, actual only 5 — planner OVERestimated
        var plan = planNode("Seq Scan", 1000, 5);
        var fragment = fragment("f1", "orders");

        List<DiagnosticFinding> findings = rule.analyze(fragment, plan, timing(100));

        assertEquals(1, findings.size());
        assertTrue(findings.get(0).title().contains("5 actual vs 1000 estimated"),
            "When actual < plan, direction should be 'overestimate' but was: " + findings.get(0).title());
    }

    @Test
    void underestimate_whenActualMuchMoreThanPlan() {
        // Plan estimated 5 rows, actual 1000 — planner UNDERestimated
        var plan = planNode("Seq Scan", 5, 1000);
        var fragment = fragment("f1", "orders");

        List<DiagnosticFinding> findings = rule.analyze(fragment, plan, timing(100));

        assertEquals(1, findings.size());
        assertTrue(findings.get(0).title().contains("1000 actual vs 5 estimated"),
            "When actual > plan, direction should be 'underestimate' but was: " + findings.get(0).title());
    }

    @Test
    void noFinding_whenWithinThreshold() {
        // Plan 100, actual 80 — ratio 0.8x, within 10x threshold
        var plan = planNode("Seq Scan", 100, 80);
        var fragment = fragment("f1", "orders");

        List<DiagnosticFinding> findings = rule.analyze(fragment, plan, timing(100));

        assertTrue(findings.isEmpty());
    }

    @Test
    void mismatch_whenEstimateZeroButActualPositive() {
        var plan = planNode("Seq Scan", 0, 100);
        var fragment = fragment("f1", "orders");

        List<DiagnosticFinding> findings = rule.analyze(fragment, plan, timing(100));

        assertEquals(1,findings.size());
    }

    @Test
    void noFinding_whenPlanIsNull() {
        var fragment = fragment("f1", "orders");

        List<DiagnosticFinding> findings = rule.analyze(fragment, null, timing(100));

        assertTrue(findings.isEmpty());
    }

    @Test
    void zeroActualRowsWithPositiveEstimate_reportsHighSeverityWithoutInfinity() {
        FragmentNode fragment = fragment("f1", "SELECT * FROM t");
        PlanNode plan = planNode("Seq Scan", 1000, 0);

        List<DiagnosticFinding> findings = rule.analyze(fragment, plan, timing(100));

        assertEquals(1, findings.size());
        DiagnosticFinding finding = findings.get(0);
        assertEquals(DiagnosticRule.Severity.MEDIUM, finding.severity());
        assertTrue(finding.title().contains("0 actual"), finding.title());
        assertFalse(finding.title().contains("Infinity"), finding.title());
        assertFalse(finding.message().contains("Infinity"), finding.message());
        assertNull(finding.verificationSql());
    }
}
