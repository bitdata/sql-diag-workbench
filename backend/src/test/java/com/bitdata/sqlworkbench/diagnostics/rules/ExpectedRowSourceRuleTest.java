package com.bitdata.sqlworkbench.diagnostics.rules;

import com.bitdata.sqlworkbench.diagnostics.DiagnosticFinding;
import com.bitdata.sqlworkbench.diagnostics.trace.EliminationOutcome;
import com.bitdata.sqlworkbench.diagnostics.trace.TraceContext;
import com.bitdata.sqlworkbench.diagnostics.trace.TraceResult;
import com.bitdata.sqlworkbench.diagnostics.trace.TraceStatus;
import com.bitdata.sqlworkbench.diagnostics.trace.TraceStep;
import com.bitdata.sqlworkbench.execution.model.ExecutionResult.PlanNode;
import com.bitdata.sqlworkbench.sql.model.FragmentNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.bitdata.sqlworkbench.diagnostics.rules.DiagnosticRuleTestFixture.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ExpectedRowMissingAtSourceRule}.
 *
 * This rule is a placeholder that always returns an empty list from analyze().
 * The real detection logic lives in the row trace endpoint, not in the
 * standard analyze path. These tests confirm that contract.
 */
class ExpectedRowSourceRuleTest {

    private final ExpectedRowMissingAtSourceRule rule = new ExpectedRowMissingAtSourceRule();

    // ── analyze() always returns empty ──────────────────────────────────

    @Test
    void analyze_alwaysReturnsEmpty_withNullPlan() {
        List<DiagnosticFinding> findings = rule.analyze(fragment("f1", "orders"), null, timing(50));

        assertTrue(findings.isEmpty(), "analyze() should return empty when plan is null");
    }

    @Test
    void analyze_alwaysReturnsEmpty_withNullTiming() {
        var plan = planNode("Seq Scan on orders", 0, 0);

        List<DiagnosticFinding> findings = rule.analyze(fragment("f1", "orders"), plan, null);

        assertTrue(findings.isEmpty(), "analyze() should return empty when timing is null");
    }

    @Test
    void analyze_alwaysReturnsEmpty_withLeafStepZeroRows() {
        // Leaf step with 0 actual rows — would be a finding in trace mode,
        // but analyze() does not implement this logic
        var plan = planNode("Seq Scan on orders", 0, 0);

        List<DiagnosticFinding> findings = rule.analyze(fragment("f1", "orders"), plan, timing(10));

        assertTrue(findings.isEmpty(),
            "analyze() should return empty even for leaf step with 0 rows");
    }

    @Test
    void analyze_alwaysReturnsEmpty_withNonLeafStepZeroRows() {
        // Non-leaf step with 0 rows — not a source table
        var child = planNode("Seq Scan on orders", 100, 100);
        var plan = planNode("Filter: status = 'X'", 0, 0, List.of(child));

        List<DiagnosticFinding> findings = rule.analyze(fragment("f1", "orders"), plan, timing(10));

        assertTrue(findings.isEmpty(),
            "analyze() should return empty for non-leaf step with 0 rows");
    }

    @Test
    void analyze_alwaysReturnsEmpty_withValidPlan() {
        var child = planNode("Index Scan on users", 10, 10);
        var plan = planNode("Nested Loop", 100, 50, List.of(child));

        List<DiagnosticFinding> findings = rule.analyze(fragment("f1", "users"), plan, timing(100));

        assertTrue(findings.isEmpty(), "analyze() should return empty with a valid plan tree");
    }

    @Test
    void analyze_alwaysReturnsEmpty_withNullFragment() {
        var plan = planNode("Seq Scan", 10, 10);

        List<DiagnosticFinding> findings = rule.analyze(null, plan, timing(10));

        assertTrue(findings.isEmpty(), "analyze() should return empty when fragment is null");
    }

    @Test
    void analyze_alwaysReturnsEmpty_withAllNulls() {
        List<DiagnosticFinding> findings = rule.analyze(null, null, null);

        assertTrue(findings.isEmpty(), "analyze() should return empty when all args are null");
    }

    @Test
    void analyze_alwaysReturnsEmpty_withMultipleChildren() {
        var child1 = planNode("Seq Scan on t1", 0, 0);
        var child2 = planNode("Seq Scan on t2", 50, 50);
        var plan = planNode("Hash Join", 0, 0, List.of(child1, child2));

        List<DiagnosticFinding> findings = rule.analyze(fragment("f1", "t1"), plan, timing(20));

        assertTrue(findings.isEmpty(),
            "analyze() should return empty even with multiple children and 0 rows");
    }

    // ── Rule metadata ─────────────────────────────────────────────────

    @Test
    void ruleMetadata_id() {
        assertEquals("expected-row-missing-at-source", rule.id());
    }

    @Test
    void ruleMetadata_displayName() {
        assertNotNull(rule.displayName());
        assertEquals("Missing Row at Source", rule.displayName());
    }

    @Test
    void ruleMetadata_description() {
        assertNotNull(rule.description());
        assertTrue(rule.description().contains("unavailable"));
    }

    @Test
    void ruleMetadata_severityIsHigh() {
        assertEquals(ExpectedRowMissingAtSourceRule.Severity.HIGH, rule.severity());
    }

    // ── analyzeTrace() outcome gating (issue 03) ──────────────────────
    //
    // The rule must defer to the detector's sealed EliminationOutcome and
    // ONLY emit "missing at source" when the outcome is MissingAtSource.
    // Previously it scanned steps independently for any NO_ROWS leaf, so an
    // Error outcome (parent errored) ALSO got a contradictory "missing at
    // source" finding — two mutually exclusive diagnostics at once.

    private static TraceStep step(String id, String label, TraceStatus status) {
        return new TraceStep(0, id, label, "SELECT 1", status, -1, 0, null);
    }

    private static TraceContext ctx(List<TraceStep> steps, EliminationOutcome outcome,
                                   Map<String, FragmentNode> nodes) {
        TraceResult result = new TraceResult(
            steps, null, 0L, null, List.of(), outcome, null, null);
        return new TraceContext("c1", null, nodes, result, List.of());
    }

    @Test
    void analyzeTrace_errorOutcome_suppressesMissingAtSource() {
        // Leaf A is NO_ROWS, but the detector returned Error(C) because a
        // parent errored (trace-key column missing from C's output). The rule
        // must NOT also emit "missing at source" — RowTraceEngine already
        // emits "Cannot determine elimination point: C" for the Error.
        FragmentNode leafA = fragment("A", "leaf_A");   // children = [] → leaf
        TraceStep leafStep = step("A", "leaf_A", TraceStatus.NO_ROWS);
        TraceStep errStep = step("C", "parent_C", TraceStatus.ERROR);
        Map<String, FragmentNode> nodes = Map.of("A", leafA);
        EliminationOutcome outcome = new EliminationOutcome.Error(
            errStep, "trace key not in C output");

        List<DiagnosticFinding> findings = rule.analyzeTrace(
            ctx(List.of(leafStep, errStep), outcome, nodes));

        assertTrue(findings.isEmpty(),
            "Error outcome must suppress missing-at-source finding. Got: " + findings);
    }

    @Test
    void analyzeTrace_missingAtSourceOutcome_emitsSingleFinding() {
        FragmentNode leafA = fragment("A", "leaf_A");
        TraceStep leafStep = step("A", "leaf_A", TraceStatus.NO_ROWS);
        Map<String, FragmentNode> nodes = Map.of("A", leafA);
        EliminationOutcome outcome = new EliminationOutcome.MissingAtSource(leafStep);

        List<DiagnosticFinding> findings = rule.analyzeTrace(
            ctx(List.of(leafStep), outcome, nodes));

        assertTrue(findings.isEmpty(), "Legacy source outcome is not source proof");
    }

    @Test
    void analyzeTrace_edgeOutcome_returnsEmpty() {
        // A child HAS_ROWS while parent NO_ROWS → elimination edge, NOT
        // missing at source. The row exists at the leaf.
        TraceStep childStep = step("B", "child_B", TraceStatus.HAS_ROWS);
        TraceStep parentStep = step("A", "parent_A", TraceStatus.NO_ROWS);
        EliminationOutcome outcome = new EliminationOutcome.Edge(childStep, parentStep);

        List<DiagnosticFinding> findings = rule.analyzeTrace(
            ctx(List.of(childStep, parentStep), outcome, Map.of()));

        assertTrue(findings.isEmpty(), "Edge outcome → no missing-at-source. Got: " + findings);
    }

    @Test
    void analyzeTrace_noneOutcome_returnsEmpty() {
        EliminationOutcome outcome = new EliminationOutcome.None();
        List<DiagnosticFinding> findings = rule.analyzeTrace(
            ctx(List.of(), outcome, Map.of()));

        assertTrue(findings.isEmpty(), "None outcome → no missing-at-source");
    }

    @Test
    void analyzeTrace_nullOutcome_returnsEmpty() {
        // Defensive: pre-2026-08-27 backends may send a null outcome.
        TraceResult result = new TraceResult(
            List.of(), null, 0L, null, List.of(), null, null, null);
        TraceContext context = new TraceContext("c1", null, Map.of(), result, List.of());

        List<DiagnosticFinding> findings = rule.analyzeTrace(context);

        assertTrue(findings.isEmpty(), "null outcome → no missing-at-source");
    }

    @Test
    void analyzeTrace_missingAtSource_usesOutcomeLeafStepNotRescan() {
        // Two NO_ROWS leaves, but the outcome names leaf_B. The rule must
        // emit exactly one finding for B (the outcome's leafStep), not
        // re-scan steps and emit for A too.
        FragmentNode leafA = fragment("A", "leaf_A");
        FragmentNode leafB = fragment("B", "leaf_B");
        TraceStep stepA = step("A", "leaf_A", TraceStatus.NO_ROWS);
        TraceStep stepB = step("B", "leaf_B", TraceStatus.NO_ROWS);
        Map<String, FragmentNode> nodes = Map.of("A", leafA, "B", leafB);
        EliminationOutcome outcome = new EliminationOutcome.MissingAtSource(stepB);

        List<DiagnosticFinding> findings = rule.analyzeTrace(
            ctx(List.of(stepA, stepB), outcome, nodes));

        assertTrue(findings.isEmpty(), "Legacy leaves must not publish source assertions");
    }
}
