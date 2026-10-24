// backend/src/test/java/com/bitdata/sqlworkbench/integration/PlanBasedRulesPostgresIntegrationTest.java
package com.bitdata.sqlworkbench.integration;

import com.bitdata.sqlworkbench.connection.ConnectionConfig;
import com.bitdata.sqlworkbench.connection.ConnectionRequest;
import com.bitdata.sqlworkbench.connection.ConnectionService;
import com.bitdata.sqlworkbench.connection.KeychainStore;
import com.bitdata.sqlworkbench.diagnostics.DiagnosticFinding;
import com.bitdata.sqlworkbench.diagnostics.RuleRegistry;
import com.bitdata.sqlworkbench.execution.ExecutionEngine;
import com.bitdata.sqlworkbench.execution.model.ExecutionResult;
import com.bitdata.sqlworkbench.execution.model.ExecutionStatus;
import com.bitdata.sqlworkbench.integration.support.IntegrationFixtureSupport;
import com.bitdata.sqlworkbench.sql.model.FragmentNode;
import com.bitdata.sqlworkbench.sql.model.FragmentType;
import com.bitdata.sqlworkbench.testsupport.Dialect;
import com.bitdata.sqlworkbench.testsupport.RequiresTestDatabase;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * A1 重定标（spec §4.6）on a real PostgreSQL: closes the gap between synthetic
 * PlanNode fixtures and real EXPLAIN output by running the plan-based rule
 * through the analyze pipeline on a live plan — {@code SequentialScanRule}
 * must fire on a real Seq-Scan-shaped plan, and must NOT false-positive on a
 * non-Seq-Scan plan.
 *
 * <p>The positive leg uses the orders⋈customers JOIN over the fixture tables:
 * {@code sqldiag_it_orders} intentionally has NO index on {@code customer_id}
 * (schema.sql L19-20) and both tables are tiny (20/5 rows, seed.sql), so PG
 * cost model picks Seq Scan for them. The rule-firing half goes through
 * {@link RuleRegistry#analyzeAll} — the same pipeline
 * DiagnosticsController.analyze drives (FullChainPostgresIntegrationTest#step5)
 * — so a plan the parser mis-shapes cannot pass silently.
 *
 * <p>Deviations from the Task 9 brief's draft code (per task-9 controller
 * rulings R1/R2, which themselves follow spec §4.6 over the plan code block):
 * the brief asserted plan TEXT shape via {@code plan().toString().contains(
 * "Seq Scan")} / {@code "actual rows="} — PlanNode is a parsed record
 * (ExecutionResult.java L19-27) whose {@code operation} carries only the PG
 * "Node Type" value (PostgreSqlPlanAdapter.java L52), so raw-text substrings
 * like {@code "actual rows="} never appear and toString coupling is brittle.
 * Replaced with typed recursive walks + actual rule firing through
 * RuleRegistry. The negative ("不误报") leg is a deterministic synthetic
 * Index-Scan plan instead of a second live query, so the test does not fight
 * the planner. Lifecycle corrections mirror Tasks 7/8 (see
 * FullChainPostgresIntegrationTest javadoc): top-level ExecutionStatus,
 * port from the fixture entry, PER_CLASS + @BeforeAll fixture, @BeforeEach
 * keychain re-stub.
 */
@SpringBootTest(properties = {
    "sql-workbench.data-dir=${java.io.tmpdir}/sqldiag-it-pbr-pg"
})
@RequiresTestDatabase(Dialect.POSTGRESQL)
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PlanBasedRulesPostgresIntegrationTest {

    /** Same matcher as SequentialScanRule.SEQ_SCAN_PATTERN (SequentialScanRule.java L21). */
    private static final Pattern SEQ_SCAN_PATTERN = Pattern.compile("Seq Scan", Pattern.CASE_INSENSITIVE);

    private static final String JOIN_SQL =
        "SELECT * FROM sqldiag_it_orders o JOIN sqldiag_it_customers c ON o.customer_id = c.id";

    @MockBean KeychainStore keychain;

    @Autowired ConnectionService connectionService;
    @Autowired ExecutionEngine engine;
    @Autowired RuleRegistry ruleRegistry;

    private IntegrationFixtureSupport.Connection pgConn;
    private String connectionId;

    @BeforeAll
    void setUpFixture() throws Exception {
        pgConn = IntegrationFixtureSupport.connect(Dialect.POSTGRESQL);
        IntegrationFixtureSupport.applyFixture(Dialect.POSTGRESQL, pgConn);
        when(keychain.retrieve(anyString())).thenReturn(pgConn.entry().password());
        // Connection once per class (shared by both live legs); port from the
        // fixture entry, not a hardcoded 5432 (Task 7/8 correction R4).
        ConnectionConfig config = connectionService.create(new ConnectionRequest(
            "pbr-pg-" + System.nanoTime(),
            pgConn.entry().host(), pgConn.entry().port(), pgConn.entry().database(),
            pgConn.entry().username(), pgConn.entry().password(),
            "postgresql", pgConn.entry().connectionType(),
            pgConn.entry().trustServerCertificate()));
        connectionId = config.id();
    }

    @AfterAll
    void tearDownFixture() throws Exception {
        if (pgConn != null) {
            IntegrationFixtureSupport.dropFixture(Dialect.POSTGRESQL, pgConn);
        }
    }

    @BeforeEach
    void restubKeychain() {
        // @MockBean mocks are reset before each test method (same trap as
        // FullChainPostgresIntegrationTest#restubKeychain) — re-arm here.
        when(keychain.retrieve(anyString())).thenReturn(pgConn.entry().password());
    }

    @Test
    void seqScanRuleFiresOnRealSeqScanPlanThroughAnalyzePipeline() throws Exception {
        // Actual mode is explicitly requested; default mode is estimated.
        // PG strategy's
        // getExplainSql = "EXPLAIN (ANALYZE true, FORMAT JSON)"
        // (ExecutionEngine.java L320-324 → PostgreSqlStrategy.java L22), i.e.
        // actuals ARE captured on this path.
        ExecutionResult r = IntegrationFixtureSupport.pollExecutionTerminal(
            engine, engine.analyze(JOIN_SQL, connectionId, null, null, "actual"), "analyze");
        assertEquals(ExecutionStatus.COMPLETED, r.status(),
            () -> "status=" + r.status() + ", error=" + r.error() + ", planError=" + r.planError());
        assertNotNull(r.plan(),
            () -> "analyze should return a parsed plan; planError=" + r.planError());
        ExecutionResult.PlanNode plan = r.plan();

        // (a) Real-plan shape: at least one node whose operation matches the
        // rule's own Seq Scan pattern. No-index 20-row/5-row tables (schema.sql
        // L19-20, seed.sql) make Seq Scan the only sane PG choice at this size;
        // if the planner ever disagrees, fail loudly with the plan shown.
        assertTrue(anyNodeMatches(plan, n -> n.operation() != null
                && SEQ_SCAN_PATTERN.matcher(n.operation()).find()),  // null-guard as in SequentialScanRule.java L92/L112
            () -> "expected a Seq Scan node on no-index fixture tables, got plan: " + plan);

        // (b) Rule firing through the analyze pipeline (spec §4.6 「触发」half).
        // SequentialScanRule is @Component-registered (SequentialScanRule.java
        // L18-19) and discovered via RuleRegistry.getBeansOfType (RuleRegistry.java
        // L30-38); its id() is "sequential-scan" (SequentialScanRule.java L27-29).
        // FragmentNode only needs id/label — the rule reads fragment.label() as
        // table-name fallback (SequentialScanRule.java L56-57) and fragment.id()
        // for finding identity (L67, L80); construction mirrors
        // FullChainPostgresIntegrationTest#step5.
        FragmentNode fragment = new FragmentNode(
            "pbr-root", FragmentType.ORIGINAL, "Root", JOIN_SQL,
            true, null, 0, null, List.of());
        List<DiagnosticFinding> findings = ruleRegistry.analyzeAll(fragment, plan, r.timing());
        assertTrue(findings.stream().anyMatch(f -> "sequential-scan".equals(f.ruleId())),
            () -> "SequentialScanRule must fire on a plan containing a Seq Scan node "
                + "(rule matches operation via SEQ_SCAN_PATTERN, L52/L106-113). "
                + "Findings: " + findings + ", plan: " + plan);

        // (c) EXPLAIN ANALYZE actuals survive parsing: the join emits 20 rows
        // (every order has a valid customer_id 1..5, seed.sql L7-34), and the
        // PG adapter maps "Actual Rows" → PlanNode.actualRows
        // (PostgreSqlPlanAdapter.java L57; unit-pinned by
        // PostgreSqlPlanAdapterTest#parseSimpleSeqScan L37). This replaces the
        // brief's unreliable toString().contains("actual rows=") check.
        assertTrue(anyNodeMatches(plan, n -> n.actualRows() > 0),
            () -> "EXPLAIN (ANALYZE true) must populate actualRows somewhere in the "
                + "tree; plan: " + plan);
    }

    @Test
    void seqScanRuleDoesNotFalsePositiveOnNonSeqScanPlan() {
        // 不误报 half: deterministic synthetic control — no planner dependence.
        // PlanNode component order: (operation, startupCost, totalCost,
        // planRows, actualRows, actualDuration, children) — ExecutionResult.java
        // L19-27; realistic 20-row costs/rows per the fixture tables.
        ExecutionResult.PlanNode indexScanPlan = new ExecutionResult.PlanNode(
            "Index Scan using sqldiag_it_orders_pkey on sqldiag_it_orders",
            0.0, 8.0, 20L, 20L, 1L, List.of());
        FragmentNode fragment = new FragmentNode(
            "pbr-neg-root", FragmentType.ORIGINAL, "Root",
            "SELECT * FROM sqldiag_it_orders", true, null, 0, null, List.of());

        List<DiagnosticFinding> findings = ruleRegistry.analyzeAll(
            fragment, indexScanPlan, new ExecutionResult.Timing(5L));
        // SequentialScanRule's gate is SEQ_SCAN_PATTERN.find() on the operation
        // (SequentialScanRule.java L21, L52, L106-108); "Index Scan using …"
        // contains no "Seq Scan" substring (case-insensitively), so the rule
        // must return no finding — if it appears, the false-positive guard broke.
        assertTrue(findings.stream().noneMatch(f -> "sequential-scan".equals(f.ruleId())),
            () -> "sequential-scan must NOT fire on a non-Seq-Scan plan. "
                + "Findings: " + findings);
    }

    /** Recursive walk over the parsed plan tree (children null-guarded). */
    private static boolean anyNodeMatches(ExecutionResult.PlanNode node,
                                          Predicate<ExecutionResult.PlanNode> predicate) {
        if (node == null) return false;
        if (predicate.test(node)) return true;
        List<ExecutionResult.PlanNode> children = node.children();
        if (children != null) {
            for (ExecutionResult.PlanNode child : children) {
                if (anyNodeMatches(child, predicate)) return true;
            }
        }
        return false;
    }

    // pollExecution is provided by IntegrationFixtureSupport.pollExecutionTerminal
    // (Task review M-1 / debt-convergence-2026-09-09 — was duplicated across all
    // 4 live IT classes).
}
