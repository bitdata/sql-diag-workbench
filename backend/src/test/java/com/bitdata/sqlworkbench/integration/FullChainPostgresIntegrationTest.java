// backend/src/test/java/com/bitdata/sqlworkbench/integration/FullChainPostgresIntegrationTest.java
package com.bitdata.sqlworkbench.integration;

import com.bitdata.sqlworkbench.connection.ConnectionConfig;
import com.bitdata.sqlworkbench.connection.ConnectionRequest;
import com.bitdata.sqlworkbench.connection.ConnectionService;
import com.bitdata.sqlworkbench.connection.KeychainStore;
import com.bitdata.sqlworkbench.diagnostics.DiagnosticsController;
import com.bitdata.sqlworkbench.diagnostics.DiagnosticFinding;
import com.bitdata.sqlworkbench.diagnostics.trace.EliminationOutcome;
import com.bitdata.sqlworkbench.diagnostics.trace.KeyValue;
import com.bitdata.sqlworkbench.diagnostics.trace.RowTraceEngine;
import com.bitdata.sqlworkbench.diagnostics.trace.TraceProgress;
import com.bitdata.sqlworkbench.diagnostics.trace.TraceRequest;
import com.bitdata.sqlworkbench.diagnostics.trace.TraceStatus;
import com.bitdata.sqlworkbench.diagnostics.trace.TraceStore;
import com.bitdata.sqlworkbench.execution.ExecutionEngine;
import com.bitdata.sqlworkbench.execution.model.ExecutionResult;
import com.bitdata.sqlworkbench.execution.model.ExecutionStatus;
import com.bitdata.sqlworkbench.integration.support.IntegrationFixtureSupport;
import com.bitdata.sqlworkbench.sql.SqlParseService;
import com.bitdata.sqlworkbench.sql.diff.ChangeAttribution;
import com.bitdata.sqlworkbench.sql.diff.DiffResult;
import com.bitdata.sqlworkbench.sql.model.FragmentNode;
import com.bitdata.sqlworkbench.sql.model.FragmentType;
import com.bitdata.sqlworkbench.testsupport.Dialect;
import com.bitdata.sqlworkbench.testsupport.RequiresTestDatabase;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Full pipeline contract test on a real PostgreSQL — covers decompose →
 * execute → diff → trace → diagnose (spec §4.3). Skips if test-databases.json
 * has no usable postgresql entry.
 *
 * <p>Deviations from the Task 7 brief's draft code (which was written against
 * an imagined API): see task-7-report.md. Notably, the class uses
 * {@code PER_CLASS} lifecycle and creates the shared connection in
 * {@code @BeforeAll} so no test depends on another test's execution order
 * (the brief's "connectionId set in step2, read in steps 4-5" is broken under
 * JUnit's default per-method instances).
 */
@SpringBootTest(properties = {
    "sql-workbench.data-dir=${java.io.tmpdir}/sqldiag-it-fc-pg"
})
@RequiresTestDatabase(Dialect.POSTGRESQL)
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FullChainPostgresIntegrationTest {

    @MockBean KeychainStore keychain;

    @Autowired ConnectionService connectionService;
    @Autowired ExecutionEngine engine;
    @Autowired SqlParseService parseService;
    @Autowired RowTraceEngine rowTrace;
    @Autowired TraceStore traceStore;
    @Autowired DiagnosticsController diagnostics;

    private IntegrationFixtureSupport.Connection pgConn;
    private String connectionId;

    @org.junit.jupiter.api.BeforeAll
    void setUpFixture() throws Exception {
        pgConn = IntegrationFixtureSupport.connect(Dialect.POSTGRESQL);
        IntegrationFixtureSupport.applyFixture(Dialect.POSTGRESQL, pgConn);
        when(keychain.retrieve(anyString())).thenReturn(pgConn.entry().password());
        // Create the connection once for the whole class — steps 2/4/5 all need
        // it and must not depend on each other's execution order.
        ConnectionConfig config = connectionService.create(new ConnectionRequest(
            "fc-pg-" + System.nanoTime(),
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
        // @MockBean mocks are reset before each test method, which would wipe
        // the stub installed in @BeforeAll; re-arm it here so
        // ConnectionService.openJdbcConnection keeps working in every test.
        when(keychain.retrieve(anyString())).thenReturn(pgConn.entry().password());
    }

    @Test
    void step1_decomposeThreeCteChain() throws Exception {
        // regional ← regional_paid (dependency), big_orders independent. Per
        // CteExpansionRule minimum-split semantics (CteExpansionRuleTest.
        // v1Standalone_v3WithV2_bodyDropped — documented for this exact shape)
        // the transitively-depended-on `regional` is NOT a direct sibling; it
        // is inlined into regional_paid's fragment. Direct siblings:
        // big_orders + regional_paid(with deps) = 2 nodes, body dropped.
        String sql = """
            WITH regional AS (
                SELECT c.region, SUM(o.amount) AS total
                FROM sqldiag_it_orders o JOIN sqldiag_it_customers c ON o.customer_id = c.id
                GROUP BY c.region
            ),
            big_orders AS (
                SELECT id, amount FROM sqldiag_it_orders WHERE amount > 100
            ),
            regional_paid AS (
                SELECT region, total FROM regional WHERE total > 50
            )
            SELECT region, total FROM regional_paid ORDER BY region
            """;
        List<FragmentNode> nodes = parseService.decompose(sql, "postgresql", "cte-expansion");
        assertNotNull(nodes, "decompose result must not be null");
        assertTrue(nodes.size() >= 2,
            () -> "decompose should produce ≥2 nodes (CTE expansion), got " + nodes.size());
        for (FragmentNode node : nodes) {
            assertNotNull(node.sqlFragment(), "every CTE node must carry SQL");
            assertFalse(node.sqlFragment().isBlank(), "CTE node SQL must not be blank");
            assertTrue(node.executable(), node.label() + " should be executable");
        }
    }

    @Test
    void step2_executeReturnsRealRows() throws Exception {
        String execId = engine.execute(
            "SELECT count(*) AS n FROM sqldiag_it_orders WHERE status = 'paid'", connectionId);
        ExecutionResult result = IntegrationFixtureSupport.pollExecutionTerminal(engine, execId, "execution");
        assertEquals(ExecutionStatus.COMPLETED, result.status(),
            () -> "status=" + result.status() + ", error=" + result.error());
        assertNotNull(result.rows());
        assertEquals(1, result.rows().size(), "count(*) returns 1 row");
        // rows() is List<List<Object>> (positional), not maps — see
        // ExecutionResult record. paid rows = 14 per seed.sql
        // (id 1,2,4,6,7,9,10,11,13,14,16,17,18,19).
        assertEquals(14, ((Number) result.rows().get(0).get(0)).intValue(),
            () -> "expected 14 paid rows; result rows: " + result.rows());
    }

    @Test
    void step3_attributeChangeReturnsOpsAndDegradedField() throws Exception {
        String before = "SELECT id, name FROM sqldiag_it_customers WHERE region = 'us-east'";
        String after  = "SELECT id, name, region FROM sqldiag_it_customers WHERE region = 'us-east'";
        DiffResult result = ChangeAttribution.attributeChangeDetailed(before, after, "postgresql");
        assertNotNull(result.ops(), "ops set should be present");
        assertFalse(result.ops().isEmpty(), "adding a column should produce ≥1 op");
        // observable degradation flag (CLAUDE.md rule #2)
        assertNotNull(result.method(), "DiffResult.method should be populated for observability");
    }

    @Test
    void step4_rowTraceProducesTerminalOutcome() throws Exception {
        // Nested single-root tree (K4b guard rejects flat/parentIds-only data);
        // construction style mirrors RowTraceEngineTest#leaf/#parent.
        FragmentNode leaf = new FragmentNode(
            "leaf-amount-filter", FragmentType.STRIPPED, "amount > 9999",
            "SELECT id FROM sqldiag_it_orders WHERE amount > 9999",
            true, null, 0, null, List.of());
        FragmentNode root = new FragmentNode(
            "fc-root", FragmentType.ORIGINAL, "Root",
            "SELECT id FROM sqldiag_it_orders",
            true, null, 1, null, List.of(leaf));

        String treeJson = new ObjectMapper().writeValueAsString(root);
        // Key id=999 is absent from both submitted queries; this is not source proof.
        TraceRequest req = new TraceRequest(
            connectionId, treeJson, List.of(new KeyValue("id", 999)), 30);

        String traceId = rowTrace.submit(req);
        TraceProgress progress = pollTraceTerminal(traceId, 30_000);
        assertEquals(TraceStatus.COMPLETED, progress.status(),
            () -> "error=" + progress.error() + ", errorReason=" + progress.errorReason());
        assertTrue(progress.steps().size() >= 2,
            () -> "root + leaf must both produce steps, got " + progress.steps());

        // Both queries report NO_ROWS; no observed boundary establishes a cause.
        assertNotNull(progress.eliminationOutcome(), "terminal outcome must be populated");
        assertInstanceOf(EliminationOutcome.Inconclusive.class, progress.eliminationOutcome(),
            () -> "expected Inconclusive for absent key, got " + progress.eliminationOutcome());
    }

    @Test
    void step5_diagnosticsControllerRunsAllRules() throws Exception {
        FragmentNode fragment = new FragmentNode(
            "diag-root", FragmentType.ORIGINAL, "Root",
            "SELECT id FROM sqldiag_it_orders WHERE amount > 9999",
            true, null, 0, null, List.of());
        // Minimal but structurally valid plan/timing so plan-based rules get
        // data instead of relying on RuleRegistry's per-rule exception catch.
        ExecutionResult.PlanNode plan = new ExecutionResult.PlanNode(
            "Seq Scan on sqldiag_it_orders", 0.0, 8.0, 20L, 0L, 1L, List.of());
        ExecutionResult.Timing timing = new ExecutionResult.Timing(5L);

        DiagnosticsController.AnalyzeResponse resp = diagnostics.analyze(
            new DiagnosticsController.AnalyzeRequest(fragment, plan, timing, null, null));
        assertNotNull(resp, "diagnostics response must not be null");
        assertNotNull(resp.findings(), "findings list should be present");
        assertEquals("UNVERIFIED",resp.evaluation().sourceState());
        assertEquals(7,resp.evaluation().rules().size());
        assertTrue(resp.findings().stream().noneMatch(f -> "sequential-scan".equals(f.ruleId())),
            "Client supplied synthetic plans are not authenticated evidence");
        for (DiagnosticFinding finding : resp.findings()) {
            assertNotNull(finding.ruleId(), "every finding must carry a ruleId");
            assertFalse(finding.ruleId().isBlank(), "finding ruleId must not be blank");
            assertNotNull(finding.severity(), "every finding must carry a severity");
        }
    }

    /** Same contract as {@link IntegrationFixtureSupport#pollExecutionTerminal}: returns a terminal progress or throws. */
    private TraceProgress pollTraceTerminal(String traceId, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            TraceProgress p = traceStore.toProgress(traceId);
            if (p != null) {
                TraceStatus s = p.status();
                if (s == TraceStatus.COMPLETED || s == TraceStatus.FAILED || s == TraceStatus.CANCELLED) {
                    return p;
                }
            }
            Thread.sleep(100);
        }
        throw new AssertionError("trace did not reach terminal state in " + timeoutMs + "ms: " + traceId);
    }
}
