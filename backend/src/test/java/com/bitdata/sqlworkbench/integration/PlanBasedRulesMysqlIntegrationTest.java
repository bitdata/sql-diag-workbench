// backend/src/test/java/com/bitdata/sqlworkbench/integration/PlanBasedRulesMysqlIntegrationTest.java
package com.bitdata.sqlworkbench.integration;

import com.bitdata.sqlworkbench.connection.ConnectionConfig;
import com.bitdata.sqlworkbench.connection.ConnectionRequest;
import com.bitdata.sqlworkbench.connection.ConnectionService;
import com.bitdata.sqlworkbench.connection.KeychainStore;
import com.bitdata.sqlworkbench.execution.ExecutionEngine;
import com.bitdata.sqlworkbench.execution.model.ExecutionResult;
import com.bitdata.sqlworkbench.execution.model.ExecutionStatus;
import com.bitdata.sqlworkbench.integration.support.IntegrationFixtureSupport;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * A1 重定标（spec §4.6）on a real MySQL: pins the adapter half of the gap —
 * {@code MySqlPlanAdapter} must parse live {@code EXPLAIN FORMAT=JSON} output
 * into a typed plan tree with real operation names. Rule firing is NOT
 * exercised here: plan-based rules are PG-shaped per spec §4.6「计划解析器差异」
 * (the MySQL adapter normalizes access_type to PG canonical names —
 * MySqlPlanAdapter.java L33-54 — but full rule semantics on MySQL plans is out
 * of this task's scope, and FullChainMysqlIntegrationTest already pins plan
 * non-nullity end-to-end).
 *
 * <p>Deviations from the Task 9 brief's draft code (controller rulings R2/R3/R4):
 * the brief's {@code r.plan().toString().isBlank()} assertion is replaced by a
 * typed recursive walk (≥1 node with non-blank operation). The brief's implied
 * "actuals" check is dropped: {@code engine.analyze(sql, connId)} passes
 * analyzeMode null (ExecutionEngine.java L90-91), which for MySQL selects
 * {@code getExplainSql} = plain {@code "EXPLAIN FORMAT=JSON"} — estimated mode
 * (ExecutionEngine.java L320-324 → MySqlStrategy.java L22; ANALYZE only via the
 * 5-arg overload, MySqlStrategy.java L27) — so {@code actual_rows} is absent
 * from the JSON and the adapter yields 0 (MySqlPlanAdapter.java L115; unit-
 * pinned by MySqlPlanAdapterTest#parseSimpleSelect_allAccessType_mapsToSeqScan
 * L45 {@code assertEquals(0, plan.actualRows()); // estimated mode}). Asserting
 * actuals here would pin the wrong contract; tree shape only, per R3. Other
 * corrections mirror Tasks 7/8: top-level ExecutionStatus, fixture port (not
 * hardcoded 3306), PER_CLASS lifecycle, @BeforeEach keychain re-stub.
 */
@SpringBootTest(properties = {
    "sql-workbench.data-dir=${java.io.tmpdir}/sqldiag-it-pbr-my"
})
@RequiresTestDatabase(Dialect.MYSQL)
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PlanBasedRulesMysqlIntegrationTest {

    @MockBean KeychainStore keychain;

    @Autowired ConnectionService connectionService;
    @Autowired ExecutionEngine engine;

    private IntegrationFixtureSupport.Connection myConn;
    private String connectionId;

    @BeforeAll
    void setUpFixture() throws Exception {
        myConn = IntegrationFixtureSupport.connect(Dialect.MYSQL);
        IntegrationFixtureSupport.applyFixture(Dialect.MYSQL, myConn);
        when(keychain.retrieve(anyString())).thenReturn(myConn.entry().password());
        // Port comes from the fixture entry, not a hardcoded 3306 (R4).
        ConnectionConfig config = connectionService.create(new ConnectionRequest(
            "pbr-my-" + System.nanoTime(),
            myConn.entry().host(), myConn.entry().port(), myConn.entry().database(),
            myConn.entry().username(), myConn.entry().password(),
            "mysql", myConn.entry().connectionType(),
            myConn.entry().trustServerCertificate()));
        connectionId = config.id();
    }

    @AfterAll
    void tearDownFixture() throws Exception {
        if (myConn != null) {
            IntegrationFixtureSupport.dropFixture(Dialect.MYSQL, myConn);
        }
    }

    @BeforeEach
    void restubKeychain() {
        // @MockBean mocks are reset before each test method — re-arm the stub
        // so ConnectionService.openJdbcConnection keeps working (Task 7/8 pattern).
        when(keychain.retrieve(anyString())).thenReturn(myConn.entry().password());
    }

    @Test
    void mySqlPlanAdapterParsesRealExplainOutputIntoTypedTree() throws Exception {
        // Single-table filtered scan: MySQL reports access_type "ALL" → the
        // adapter maps it to canonical "Seq Scan" and attaches a "Filter" child
        // for the attached_condition (MySqlPlanAdapter.java L37-54, L110-125;
        // unit-pinned by MySqlPlanAdapterTest#parseSimpleSelect_…).
        String execId = engine.analyze(
            "SELECT * FROM sqldiag_it_orders WHERE status = 'paid'", connectionId);
        ExecutionResult r = IntegrationFixtureSupport.pollExecutionTerminal(engine, execId, "analyze");
        // On the analyze failure path ExecutionEngine carries the message in `error`
        // (planError stays null; ExecutionEngine#analyzeErrorResult), so surface both
        // fields on assertion failure.
        assertEquals(ExecutionStatus.COMPLETED, r.status(),
            () -> "status=" + r.status() + ", error=" + r.error() + ", planError=" + r.planError());
        // On the COMPLETED path plan and planError are mutually exclusive —
        // parse-null sets planError, a parsed tree leaves it null
        // (ExecutionEngine.java L326-346). Pin both explicitly per R3.
        assertNotNull(r.plan(),
            () -> "MySqlPlanAdapter must return a plan tree on real MySQL; planError="
                + r.planError());
        assertNull(r.planError(), () -> "planError must be null on a parsed plan: " + r.planError());
        // A non-null root alone would not prove the JSON→tree mapping worked
        // beyond parse()'s early return (MySqlPlanAdapter.java L61-78); the
        // tree must carry at least one node with a real, non-blank operation.
        assertTrue(hasNodeWithOperation(r.plan()),
            () -> "plan tree must contain ≥1 node with non-blank operation; plan: " + r.plan());
    }

    /** Recursive walk: ≥1 node whose operation is non-null and non-blank. */
    private static boolean hasNodeWithOperation(ExecutionResult.PlanNode node) {
        if (node == null) return false;
        String op = node.operation();
        if (op != null && !op.isBlank()) return true;
        List<ExecutionResult.PlanNode> children = node.children();
        if (children != null) {
            for (ExecutionResult.PlanNode child : children) {
                if (hasNodeWithOperation(child)) return true;
            }
        }
        return false;
    }

    // pollExecution is provided by IntegrationFixtureSupport.pollExecutionTerminal
    // (Task review M-1 / debt-convergence-2026-09-09 — was duplicated across all
    // 4 live IT classes).
}
