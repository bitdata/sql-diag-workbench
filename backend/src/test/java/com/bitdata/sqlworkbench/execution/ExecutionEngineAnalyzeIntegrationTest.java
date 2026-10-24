package com.bitdata.sqlworkbench.execution;

import com.bitdata.sqlworkbench.connection.ConnectionConfig;
import com.bitdata.sqlworkbench.connection.ConnectionRequest;
import com.bitdata.sqlworkbench.connection.ConnectionService;
import com.bitdata.sqlworkbench.connection.KeychainStore;
import com.bitdata.sqlworkbench.execution.model.ExecutionResult;
import com.bitdata.sqlworkbench.execution.model.ExecutionStatus;
import com.bitdata.sqlworkbench.testsupport.Dialect;
import com.bitdata.sqlworkbench.testsupport.LegacyEnvVarCompatNotice;
import com.bitdata.sqlworkbench.testsupport.RequiresTestDatabase;
import com.bitdata.sqlworkbench.testsupport.TestDatabaseResolver;
import com.bitdata.sqlworkbench.testsupport.TestDatabaseResolver.DatabaseEntry;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Contract tests for the split {@code execute} / {@code analyze} API.
 *
 * <p>The tool exposes two distinct execution paths:
 * <ul>
 *   <li>{@link ExecutionEngine#execute(String, String)} runs a plain SELECT,
 *       returning rows. It does NOT run EXPLAIN — that's the user's separate
 *       "Analyze" button, which lives in Performance mode.</li>
 *   <li>{@link ExecutionEngine#analyze(String, String)} runs
 *       {@code EXPLAIN (ANALYZE true, FORMAT JSON)} on the SQL, returning
 *       the plan tree with actual stats. It does NOT return rows.</li>
 * </ul>
 *
 * <p>These tests verify the structural separation. If anyone re-merges the
 * paths (so {@code execute} also runs EXPLAIN, or {@code analyze} returns
 * rows), the relevant test fails.
 *
 * <p>Requires a live PostgreSQL entry in {@code test-databases.json} (single
 * credential source); skips with an informative reason otherwise. Before task
 * B (chore/debt-convergence-2026-09) this class was gated by per-dialect
 * legacy environment variables — that legacy workflow is now migration notice
 * only, surfaced by
 * {@link LegacyEnvVarCompatNotice#warnIfLegacyEnvVarsStillSet()}.
 */
@SpringBootTest
@RequiresTestDatabase(Dialect.POSTGRESQL)
@Tag("integration")
class ExecutionEngineAnalyzeIntegrationTest {

    @MockBean private KeychainStore keychain;

    @Autowired private ExecutionEngine engine;
    @Autowired private ConnectionService connectionService;

    @BeforeAll
    static void warnLegacyEnv() {
        LegacyEnvVarCompatNotice.warnIfLegacyEnvVarsStillSet();
    }

    private DatabaseEntry entry() {
        return TestDatabaseResolver.resolve(Dialect.POSTGRESQL);
    }

    private String connectionId;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        DatabaseEntry entry = entry();
        when(keychain.retrieve(anyString())).thenReturn(entry.password());
        ConnectionConfig config = connectionService.create(new ConnectionRequest(
            "test-pg-" + System.nanoTime(),
            entry.host(), entry.port(), entry.database(),
            entry.username(), entry.password(),
            "postgresql", entry.connectionType(), entry.trustServerCertificate()
        ));
        connectionId = config.id();
    }

    @Test
    void executeReturnsRowsOnly_noPlanInResponse() throws Exception {
        // execute is the Data-mode path: it should return rows but NO plan.
        // Re-introducing "execute also runs EXPLAIN" would be a regression
        // (the original cancel-race bug came from doing both).
        //
        // SQL restored (pg-cast-babel-and-ticket-sweep PR-1, 2026-09-10):
        // `n::int` / `n::text` are PG-native cast syntax. The previous
        // live-channel-assumption-sweep PR-2 rewrite to `CAST(n AS int)`
        // was a temporary workaround for Calcite 1.37's default parser
        // grammar gap on `::`; PR-1 routes PG through Calcite's Babel
        // parser factory (CALCITE-2843), which accepts `::` natively —
        // closing spec deviation #1 (live-channel-assumption-sweep-design/
        // spec-deviations.md). Bug ① end-to-end closed.
        String executionId = engine.execute(
            "SELECT n::int AS x, n::text AS label FROM generate_series(1, 3) AS n",
            connectionId);

        ExecutionResult result = pollUntilTerminal(executionId);

        assertEquals(ExecutionStatus.COMPLETED, result.status(),
            () -> "execute should complete. status=" + result.status()
                + ", error=" + result.error());
        assertNotNull(result.rows(), "execute should return rows");
        assertEquals(3, result.rows().size(), "SELECT generate_series(1,3) returns 3 rows");
        assertNull(result.plan(),
            () -> "execute must NOT include a plan (Analyze is a separate path). "
                + "planError=" + result.planError());
        assertNull(result.planError(),
            "execute should not surface an EXPLAIN error — it didn't run EXPLAIN");
    }

    @Test
    void analyzeReturnsPlanOnly_noRowsInResponse() throws Exception {
        // analyze is the Performance-mode path: EXPLAIN ANALYZE returns the
        // plan with actual stats. It must NOT materialize rows for the user.
        //
        // SQL restored: see `executeReturnsRowsOnly_noPlanInResponse` above —
        // `n::int` PG-native cast now parses under Babel factory.
        String executionId = engine.analyze(
            "SELECT n::int AS x FROM generate_series(1, 3) AS n",
            connectionId, null, null, "actual");

        ExecutionResult result = pollUntilTerminal(executionId);

        assertEquals(ExecutionStatus.COMPLETED, result.status(),
            () -> "analyze should complete. status=" + result.status()
                + ", error=" + result.error());
        assertNotNull(result.plan(),
            () -> "analyze should return a plan tree. planError=" + result.planError());
        // Actual stats come from ANALYZE — the root node has operation text.
        assertNotNull(result.plan().operation(),
            "Plan root should have an operation name (PG Plan output)");
        assertNull(result.rows(),
            "analyze must NOT return rows — those come from execute");
    }

    @Test
    void analyzeSurvivesSlowQueryThatPreviouslyTriggeredCancelRace() throws Exception {
        // ANALYZE re-executes the query to gather actual stats. With the old
        // single-endpoint design, the SELECT's cancel timer would hit the
        // EXPLAIN on the same connection. With the new design, analyze runs
        // EXPLAIN on its own connection — no shared statement, no race.
        // We don't shrink the timeout here; the default 30s is plenty for a
        // 500ms sleep. The test asserts the plan still arrives.
        String executionId = engine.analyze(
            "SELECT pg_sleep(0.5) AS paused", connectionId, null, null, "actual");

        ExecutionResult result = pollUntilTerminal(executionId);

        assertEquals(ExecutionStatus.COMPLETED, result.status(),
            () -> "analyze should complete. status=" + result.status()
                + ", error=" + result.error());
        assertNotNull(result.plan(),
            () -> "Plan should arrive even though ANALYZE re-runs the query. "
                + "planError=" + result.planError());
    }

    private ExecutionResult pollUntilTerminal(String executionId) throws InterruptedException {
        for (int i = 0; i < 200; i++) {  // up to 20s
            ExecutionResult r = engine.getStatus(executionId);
            if (r != null) {
                ExecutionStatus s = r.status();
                if (s == ExecutionStatus.COMPLETED
                    || s == ExecutionStatus.FAILED
                    || s == ExecutionStatus.TIMED_OUT
                    || s == ExecutionStatus.CANCELLED) {
                    return r;
                }
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Execution did not reach terminal state in 20s: " + executionId);
    }
}
