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
 * Contract tests for per-request query timeout override.
 *
 * <p>{@link ExecutionEngine#execute(String, String, Integer)} and
 * {@link ExecutionEngine#analyze(String, String, Integer)} accept a nullable
 * timeout-in-seconds. When supplied and positive, that value overrides the
 * default (configured via {@code sql-workbench.execution.query-timeout-seconds}).
 * Null, zero, or negative values fall back to the default — never to "no
 * timeout" or to a hard error.
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
class ExecutionEnginePerRequestTimeoutIntegrationTest {

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
    void executeRespectsPerRequestTimeout() throws Exception {
        // pg_sleep(3) would take 3s; with a 1s override the statement should
        // hit QueryTimeout and the engine should mark the result TIMED_OUT.
        // The error message must reference the *overridden* timeout (1s), not
        // the default 30s — proves the per-request value reached the JDBC layer.
        String executionId = engine.execute(
            "SELECT pg_sleep(3) AS x",
            connectionId,
            /* queryTimeoutSeconds */ 1);

        ExecutionResult result = pollUntilTerminal(executionId);
        assertEquals(ExecutionStatus.TIMED_OUT, result.status(),
            () -> "Expected TIMED_OUT. status=" + result.status() + ", error=" + result.error());
        assertTrue(result.error().contains("1s"),
            "error message should mention the overridden timeout of 1s: " + result.error());
    }

    @Test
    void executeFallsBackToDefaultWhenTimeoutOmitted() throws Exception {
        // pg_sleep(0.5) is well inside the default 30s window. When the caller
        // omits queryTimeoutSeconds the engine must use the configured default,
        // not 0 or "no timeout". Result must complete, not time out.
        String executionId = engine.execute(
            "SELECT pg_sleep(0.5) AS x",
            connectionId);

        ExecutionResult result = pollUntilTerminal(executionId);
        assertEquals(ExecutionStatus.COMPLETED, result.status(),
            () -> "Expected COMPLETED. status=" + result.status() + ", error=" + result.error());
    }

    @Test
    void analyzeRespectsPerRequestTimeout() throws Exception {
        // ANALYZE re-executes the query to gather actual stats, so the same
        // timeout override must reach its statement too. 1s override against a
        // 3s pg_sleep must produce TIMED_OUT with "1s" in the error message.
        String executionId = engine.analyze(
            "SELECT pg_sleep(3) AS x",
            connectionId,
            /* queryTimeoutSeconds */ 1, null, "actual");

        ExecutionResult result = pollUntilTerminal(executionId);
        assertEquals(ExecutionStatus.TIMED_OUT, result.status(),
            () -> "Expected TIMED_OUT. status=" + result.status() + ", error=" + result.error());
        assertTrue(result.error().contains("1s"),
            "error message should mention the overridden timeout of 1s: " + result.error());
    }

    @Test
    void executeAcceptsZeroOrNegativeAsFallback() throws Exception {
        // A 0 value is meaningless for setQueryTimeout (driver treats it as
        // "no timeout" on some, throws on others). The engine must treat
        // 0/negative as "use the default" instead of forwarding to JDBC.
        // pg_sleep(0.5) fits inside the 30s default — must complete.
        String executionId = engine.execute(
            "SELECT pg_sleep(0.5) AS x",
            connectionId,
            /* queryTimeoutSeconds */ 0);

        ExecutionResult result = pollUntilTerminal(executionId);
        assertEquals(ExecutionStatus.COMPLETED, result.status(),
            "queryTimeoutSeconds=0 should fall back to default (30s), not fail. "
                + "status=" + result.status() + ", error=" + result.error());
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
