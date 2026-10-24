// backend/src/test/java/com/bitdata/sqlworkbench/integration/FullChainMysqlIntegrationTest.java
package com.bitdata.sqlworkbench.integration;

import com.bitdata.sqlworkbench.connection.ConnectionConfig;
import com.bitdata.sqlworkbench.connection.ConnectionRequest;
import com.bitdata.sqlworkbench.connection.ConnectionService;
import com.bitdata.sqlworkbench.connection.KeychainStore;
import com.bitdata.sqlworkbench.execution.ExecutionEngine;
import com.bitdata.sqlworkbench.execution.model.ExecutionResult;
import com.bitdata.sqlworkbench.execution.model.ExecutionStatus;
import com.bitdata.sqlworkbench.integration.support.IntegrationFixtureSupport;
import com.bitdata.sqlworkbench.sql.SqlParseService;
import com.bitdata.sqlworkbench.sql.model.FragmentNode;
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
 * MySQL differential contract test on a real MySQL — covers the dialect-specific
 * surfaces called out in spec §4.3: backtick quoting through the
 * parse/decompose pipeline, and {@code MySqlPlanAdapter} producing a plan tree
 * from real {@code EXPLAIN FORMAT=JSON} output. Skips if test-databases.json
 * has no usable mysql entry.
 *
 * <p>Deviations from the Task 8 brief's draft code (written against an imagined
 * API, same drafting flaw as Task 7 — see FullChainPostgresIntegrationTest and
 * task-7-report.md): {@code SqlParseController.DecomposeRequest/Response} do
 * not exist, so decompose goes through {@link SqlParseService#decompose};
 * {@code ExecutionStatus} is top-level, not nested in {@code ExecutionResult};
 * {@code rows()} is positional {@code List<List<Object>>}; the port comes from
 * the fixture entry (not hardcoded 3306); and the class uses PER_CLASS
 * lifecycle with the shared connection created in {@code @BeforeAll} (the
 * brief's "connectionId set in step2, read in step3" is broken under JUnit's
 * default per-method instances).
 */
@SpringBootTest(properties = {
    "sql-workbench.data-dir=${java.io.tmpdir}/sqldiag-it-fc-my"
})
@RequiresTestDatabase(Dialect.MYSQL)
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FullChainMysqlIntegrationTest {

    @MockBean KeychainStore keychain;

    @Autowired ConnectionService connectionService;
    @Autowired ExecutionEngine engine;
    @Autowired SqlParseService parseService;

    private IntegrationFixtureSupport.Connection myConn;
    private String connectionId;

    @BeforeAll
    void setUpFixture() throws Exception {
        myConn = IntegrationFixtureSupport.connect(Dialect.MYSQL);
        IntegrationFixtureSupport.applyFixture(Dialect.MYSQL, myConn);
        when(keychain.retrieve(anyString())).thenReturn(myConn.entry().password());
        // Create the connection once for the whole class — steps 2/3 both need
        // it and must not depend on each other's execution order. Port comes
        // from the fixture entry, not a hardcoded 3306.
        ConnectionConfig config = connectionService.create(new ConnectionRequest(
            "fc-my-" + System.nanoTime(),
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
        // @MockBean mocks are reset before each test method, which would wipe
        // the stub installed in @BeforeAll; re-arm it here so
        // ConnectionService.openJdbcConnection keeps working in every test.
        when(keychain.retrieve(anyString())).thenReturn(myConn.entry().password());
    }

    @Test
    void mySqlBacktickQuotingSurvivesDecompose() throws Exception {
        // JOIN-shaped query over the two fixture tables with backticked
        // identifiers (SqlParseEngine parses "mysql" with
        // Quoting.BACK_TICK — SqlDialectResolver.parserConfig; PR-1
        // (mysql-quoting-and-ledger-correction, 2026-09-17) flipped from
        // BACK_TICK_BACKSLASH to BACK_TICK so MySQL doubled-backtick escape works).
        // Rule: join-split — JoinSplitRuleTest#mysqlDialect proves this shape
        // (root 2-table JOIN parsed as mysql) yields exactly 2 fragments.
        //
        // The brief's premise "status is a reserved keyword requiring backticks"
        // is wrong twice over: MySQL itself treats `status` as a NON-reserved
        // keyword, and this project's mysql reserved set
        // (SqlKeywords.ANSI_SQL_CORE ∪ MYSQL_EXTRA, wired via
        // MySqlStrategy.getDialectProfile) does NOT contain "status" — so
        // NodeToSql.unquoteIfSafe strips its backticks (the documented,
        // unit-tested profile behavior: JoinSplitRuleTest#mysqlIdentifiersQuoted
        // asserts simple identifiers come out WITHOUT backticks). To still pin
        // positive backtick retention, the orders alias uses `key`, which IS in
        // MYSQL_EXTRA and therefore must keep its backticks
        // (NodeToSqlTest#toSql_mysql_preservesReservedWordQuoting is the direct
        // unit evidence for reserved-word retention under the mysql profile).
        String sql = "SELECT `key`.`id`, `key`.`status`, `c`.`region` "
            + "FROM `sqldiag_it_orders` AS `key` "
            + "JOIN `sqldiag_it_customers` AS `c` ON `key`.`customer_id` = `c`.`id`";

        List<FragmentNode> nodes = parseService.decompose(sql, "mysql", "join-split");
        assertNotNull(nodes, "decompose result must not be null");
        assertTrue(nodes.size() >= 2,
            () -> "join-split must produce ≥2 fragments for a 2-table JOIN (cf. "
                + "JoinSplitRuleTest#mysqlDialect), got " + nodes.size());
        for (FragmentNode node : nodes) {
            String frag = node.sqlFragment();
            assertNotNull(frag, "every join-split fragment must carry SQL");
            assertFalse(frag.isBlank(), "join-split fragment SQL must not be blank");
            // JoinSplitRule hardcodes executable=true for standalone branches
            // (same contract branchSqlIsStandalone pins for postgresql).
            assertTrue(node.executable(), node.label() + " should be executable");
            // MySQL profile must never leak PostgreSQL double quotes —
            // NodeToSqlTest#toSql_mysql_usesBacktickQuotes pins exactly this.
            assertFalse(frag.contains("\""),
                "MySQL output must not use double quotes: " + frag);
            // Documented stripping behavior (see block comment above):
            // `status` is not reserved for this project's mysql profile, so
            // its backticks must NOT survive.
            assertFalse(frag.contains("`status`"),
                "non-reserved `status` should have backticks stripped per "
                    + "JoinSplitRuleTest#mysqlIdentifiersQuoted: " + frag);
            // The surviving leaf's FROM clause always keeps one fixture table
            // (removeLeafFromTree returns the sibling untouched).
            String lower = frag.toLowerCase();
            assertTrue(lower.contains("sqldiag_it_orders") || lower.contains("sqldiag_it_customers"),
                "each fragment must still reference its surviving table: " + frag);
        }
        // Both tables appear across the two fragments (one per branch).
        assertTrue(nodes.stream().anyMatch(n -> n.sqlFragment().toLowerCase().contains("sqldiag_it_orders")),
            "some fragment must keep sqldiag_it_orders");
        assertTrue(nodes.stream().anyMatch(n -> n.sqlFragment().toLowerCase().contains("sqldiag_it_customers")),
            "some fragment must keep sqldiag_it_customers");
        // Positive differential: the reserved-word alias `key` keeps its
        // backticks through NodeToSql (SqlKeywords.MYSQL_EXTRA +
        // NodeToSql.unquoteIfSafe; the FROM leaf passes through
        // removeLeafFromTree unchanged, so this is branch-independent).
        assertTrue(nodes.stream().anyMatch(n -> n.sqlFragment().contains("`key`")),
            () -> "reserved-word alias must keep backticks in mysql output; fragments: "
                + nodes.stream().map(FragmentNode::sqlFragment).toList());
    }

    @Test
    void mySqlExecuteReturnsRows() throws Exception {
        String execId = engine.execute(
            "SELECT count(*) AS n FROM sqldiag_it_orders WHERE status = 'paid'", connectionId);
        ExecutionResult result = IntegrationFixtureSupport.pollExecutionTerminal(engine, execId, "execution");
        assertEquals(ExecutionStatus.COMPLETED, result.status(),
            () -> "status=" + result.status() + ", error=" + result.error());
        assertNotNull(result.rows());
        assertEquals(1, result.rows().size(), "count(*) returns 1 row");
        // rows() is positional List<List<Object>> (ExecutionResult record).
        // paid rows = 14 per backend/src/test/resources/integration/mysql/
        // seed.sql (id 1,2,4,6,7,9,10,11,13,14,16,17,18,19).
        assertEquals(14, ((Number) result.rows().get(0).get(0)).intValue(),
            () -> "expected 14 paid rows; result rows: " + result.rows());
    }

    @Test
    void mySqlAnalyzeReturnsPlanWithActualStats() throws Exception {
        // Brief's name kept for traceability, but this IT only pins that
        // MySqlPlanAdapter yields a non-null plan tree from real
        // EXPLAIN FORMAT=JSON output; asserting on actual-rows/actual-time
        // fields inside the plan is Task 1.9's job.
        String execId = engine.analyze(
            "SELECT * FROM sqldiag_it_orders o JOIN sqldiag_it_customers c ON o.customer_id = c.id",
            connectionId);
        ExecutionResult result = IntegrationFixtureSupport.pollExecutionTerminal(engine, execId, "analyze");
        // On the analyze failure path ExecutionEngine carries the message in `error`
        // (planError stays null; ExecutionEngine#analyzeErrorResult), so surface both
        // fields in the failure messages.
        assertEquals(ExecutionStatus.COMPLETED, result.status(),
            () -> "status=" + result.status() + ", error=" + result.error()
                + ", planError=" + result.planError());
        assertNotNull(result.plan(),
            () -> "MySqlPlanAdapter must return a plan tree on real MySQL; planError="
                + result.planError());
    }

    // pollExecution is provided by IntegrationFixtureSupport.pollExecutionTerminal
    // (Task review M-1 / debt-convergence-2026-09-09 — was duplicated across all
    // 4 live IT classes).
}
