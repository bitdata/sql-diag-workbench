package com.bitdata.sqlworkbench.execution;

import com.bitdata.sqlworkbench.connection.ConnectTimeoutException;
import com.bitdata.sqlworkbench.connection.ConnectionConfig;
import com.bitdata.sqlworkbench.connection.ConnectionService;
import com.bitdata.sqlworkbench.connection.DatabaseStrategy;
import com.bitdata.sqlworkbench.connection.DatabaseStrategyFactory;
import com.bitdata.sqlworkbench.execution.model.ExecutionResult;
import com.bitdata.sqlworkbench.execution.model.ExecutionStatus;
import com.bitdata.sqlworkbench.sql.SelectOnlyGuard;
import com.bitdata.sqlworkbench.sql.decompose.RuleTestDeps;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * DB-free wiring tests for the timeout-classification fix (debt-convergence
 * Follow-up 2/3, 2026-09-09).
 *
 * <p>The live-DB ITs {@code ExecutionEnginePerRequestTimeoutIntegrationTest} assert
 * {@code TIMED_OUT} for a real {@code pg_sleep(3)} with a 1s override. Those tests
 * were red because (A) PG's per-request timeout surfaces as a plain
 * {@link SQLException} carrying the server's cancel-signal
 * ({@code "canceling statement due to user request"}, sqlstate {@code 57014}) — not
 * {@link java.sql.SQLTimeoutException} — so it fell into the generic FAILED catch;
 * and (B) {@code errorResultWithTiming}/{@code analyzeErrorResult} hardcoded
 * {@code FAILED} in the result, masking any {@code TIMED_OUT} set on {@code ctx}.
 *
 * <p>These tests prove the wiring without a live DB: a mocked Statement that throws
 * a cancel-signal {@link SQLException} must surface as {@code TIMED_OUT} with the
 * overridden timeout ("1s") in {@code error()}, while a generic {@link SQLException}
 * still surfaces as {@code FAILED}. Both the {@code execute} and {@code analyze}
 * paths are covered (same-class sweep).
 *
 * <p>It also carries the behavioral witnesses for defect (A) — the status/result
 * publish race (2026-09-25). Those are PROBABILISTIC: the pre-fix window is
 * microsecond-wide, so a green run is not proof. The deterministic evidence for
 * (A) is the structural pin in {@link ExecutionEnginePublishShapeTest}.
 * Spec: docs/superpowers/specs/2026-09-25-getstatus-atomic-publish-design.md §6.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ExecutionEngineTimeoutClassificationTest {

    @Mock ConnectionService connectionService;
    @Mock ExplainServiceFactory explainServiceFactory;
    @Mock DatabaseStrategyFactory strategyFactory;
    @Mock DatabaseStrategy strategy;
    @Mock Connection conn;
    @Mock Statement stmt;

    private ExecutionEngine engine;

    private static final ConnectionConfig PG_CONFIG = new ConnectionConfig(
        "conn-1", "Test", "localhost", 5432, "testdb", "user", "postgresql", null, false, "", "", "direct", false
    );

    @BeforeEach
    void setUp() throws SQLException {
        engine = new ExecutionEngine(connectionService, explainServiceFactory, strategyFactory,
            new SelectOnlyGuard(RuleTestDeps.testResolver()), 30, 100);
        when(connectionService.getById("conn-1")).thenReturn(Optional.of(PG_CONFIG));
        when(connectionService.openJdbcConnection("conn-1")).thenReturn(conn);
        var databaseMetadata=org.mockito.Mockito.mock(java.sql.DatabaseMetaData.class);
        when(conn.getMetaData()).thenReturn(databaseMetadata);
        when(databaseMetadata.getDatabaseProductName()).thenReturn("PostgreSQL");
        when(databaseMetadata.getDatabaseProductVersion()).thenReturn("16");
        when(conn.createStatement()).thenReturn(stmt);
        when(strategyFactory.get("postgresql")).thenReturn(strategy);
        when(strategy.supportsReadOnly()).thenReturn(false);
        when(explainServiceFactory.supports("postgresql")).thenReturn(true);

        when(strategy.dialect()).thenReturn("postgresql");
    }

    private ExecutionResult awaitTerminal(String executionId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            ExecutionResult r = engine.getStatus(executionId);
            if (r != null && r.status() != ExecutionStatus.QUEUED && r.status() != ExecutionStatus.RUNNING) {
                return r;
            }
            Thread.sleep(20);
        }
        fail("Execution " + executionId + " did not reach a terminal state within 5s");
        return null;
    }

    // ─── getStatus contract pins ──────────────────────────────────────────

    @Test
    void unknownExecutionIdReturnsNull() {
        assertNull(engine.getStatus("no-such-execution-id"));
    }

    @Test
    void statusIsNeverNullImmediatelyAfterSubmit() throws Exception {
        when(strategy.getExplainSql(anyString())).thenReturn("EXPLAIN (FORMAT JSON) SELECT 1");
        when(stmt.executeQuery(anyString())).thenThrow(
            new SQLException("ERROR: canceling statement due to user request", "57014"));

        String id = engine.analyze("SELECT 1", "conn-1", /*timeout*/ 1);

        // The snapshot's initial value is (null, null); submit publishes QUEUED
        // BEFORE returning the id, and no reader can hold the id any earlier (no
        // execution-listing endpoint exists). So a public reader must never see a
        // null status. See spec §4.
        ExecutionResult r = engine.getStatus(id);
        assertNotNull(r, "getStatus returned null for a just-submitted execution: " + id);
        assertNotNull(r.status(),
            "getStatus exposed a null status for " + id + " — the (null, null) snapshot "
                + "initial value leaked to a public reader (spec §4)");
    }

    // ─── execute path ─────────────────────────────────────────────────────

    @Test
    void execute_cancelSignalSurfacesAsTimedOutWithOverriddenTimeout() throws Exception {
        // PG's setQueryTimeout fires a server cancel → plain SQLException with the
        // cancel-signal text + sqlstate 57014 (NOT SQLTimeoutException).
        when(stmt.execute(anyString())).thenThrow(
            new SQLException("ERROR: canceling statement due to user request", "57014"));

        String id = engine.execute("SELECT 1", "conn-1", /*timeout*/ 1, /*maxRows*/ 100);
        ExecutionResult r = awaitTerminal(id);

        assertEquals(ExecutionStatus.TIMED_OUT, r.status(),
            () -> "cancel-signal SQLException must classify as TIMED_OUT, not FAILED. "
                + "status=" + r.status() + ", error=" + r.error());
        assertTrue(r.error().contains("1s"),
            "error must mention the overridden timeout (1s) for the user, not the raw "
                + "cancel text: " + r.error());
    }

    @Test
    void execute_genericSqlExceptionStillSurfacesAsFailed() throws Exception {
        when(stmt.execute(anyString())).thenThrow(
            new SQLException("ERROR: relation \"nope\" does not exist", "42P01"));

        String id = engine.execute("SELECT 1", "conn-1", 1, 100);
        ExecutionResult r = awaitTerminal(id);

        assertEquals(ExecutionStatus.FAILED, r.status(),
            () -> "non-timeout SQLException must remain FAILED. status=" + r.status()
                + ", error=" + r.error());
        assertTrue(r.error().contains("does not exist"),
            "FAILED error must carry the driver message: " + r.error());
    }

    // ─── analyze path ──────────────────────────────────────────────────────

    @Test
    void analyze_cancelSignalSurfacesAsTimedOutWithOverriddenTimeout() throws Exception {
        when(strategy.getExplainSql(anyString())).thenReturn("EXPLAIN (FORMAT JSON) SELECT 1");
        when(stmt.executeQuery(anyString())).thenThrow(
            new SQLException("ERROR: canceling statement due to user request", "57014"));

        String id = engine.analyze("SELECT 1", "conn-1", /*timeout*/ 1);
        ExecutionResult r = awaitTerminal(id);

        assertEquals(ExecutionStatus.TIMED_OUT, r.status(),
            () -> "analyze cancel-signal must classify as TIMED_OUT. status=" + r.status()
                + ", error=" + r.error());
        assertTrue(r.error().contains("1s"),
            "analyze error must mention the overridden timeout (1s): " + r.error());
    }

    @Test
    void analyze_genericSqlExceptionStillSurfacesAsFailed() throws Exception {
        when(strategy.getExplainSql(anyString())).thenReturn("EXPLAIN (FORMAT JSON) SELECT 1");
        when(stmt.executeQuery(anyString())).thenThrow(
            new SQLException("ERROR: relation \"nope\" does not exist", "42P01"));

        String id = engine.analyze("SELECT 1", "conn-1", 1);
        ExecutionResult r = awaitTerminal(id);

        assertEquals(ExecutionStatus.FAILED, r.status(),
            () -> "analyze non-timeout SQLException must remain FAILED. status=" + r.status()
                + ", error=" + r.error());
    }

    // ─── connect deadline must not be laundered into a query timeout ──────
    //
    // The workload connect deadline (ConnectionService.openJdbcConnection →
    // ConnectWatchdog) fires before any Statement exists. Both engine paths call
    // openJdbcConnection INSIDE the same try whose catch(SQLTimeoutException)
    // publishes TIMED_OUT / "Query timed out after Ns" — a diagnosis of a query
    // that never ran. The deadline must surface as FAILED carrying its own
    // message. Same-class sweep: execute + analyze here, RowTraceEngine in
    // RowTraceEngineTest.executeTrace_connectDeadlineIsNotAQueryTimeout.

    @Test
    void execute_connectDeadlineIsNotReportedAsAQueryTimeout() throws Exception {
        when(connectionService.openJdbcConnection("conn-1")).thenThrow(
            new ConnectTimeoutException("Connection timed out after 30s"));

        String id = engine.execute("SELECT 1", "conn-1", /*timeout*/ 1, /*maxRows*/ 100);
        ExecutionResult r = awaitTerminal(id);

        assertNotEquals(ExecutionStatus.TIMED_OUT, r.status(),
            () -> "a connect deadline must not be reported as a query timeout — that "
                + "diagnoses a query that never ran. status=" + r.status()
                + ", error=" + r.error());
        assertTrue(r.error().contains("Connection timed out after 30s"),
            "the connect deadline's own message must survive: " + r.error());
    }

    @Test
    void analyze_connectDeadlineIsNotReportedAsAQueryTimeout() throws Exception {
        when(strategy.getExplainSql(anyString())).thenReturn("EXPLAIN (FORMAT JSON) SELECT 1");
        when(connectionService.openJdbcConnection("conn-1")).thenThrow(
            new ConnectTimeoutException("Connection timed out after 30s"));

        String id = engine.analyze("SELECT 1", "conn-1", /*timeout*/ 1);
        ExecutionResult r = awaitTerminal(id);

        assertNotEquals(ExecutionStatus.TIMED_OUT, r.status(),
            () -> "analyze: a connect deadline must not be reported as a query timeout. "
                + "status=" + r.status() + ", error=" + r.error());
        assertTrue(r.error().contains("Connection timed out after 30s"),
            "analyze: the connect deadline's own message must survive: " + r.error());
    }

    // ─── publish atomicity (A): a terminal read must carry its result ─────
    //
    // Both publish paths are covered (same-class sweep, rule #1). Every path that
    // currently reaches a terminal publishes a result: the cancel() entry site (T5)
    // and the three worker checkpoints (T6) were given reason-tagged results, so no
    // site deterministically publishes a terminal with a null result. The
    // publish(status, result) signature still admits a null result, so this stays a
    // "no current site does that" statement, not a type-level guarantee.
    //
    // Iterations stay at 45 because `run`'s finally GCs the executions map only
    // when size > 50; staying under it keeps this test free of map eviction, so a
    // null getStatus can never be mistaken for a violation. One execution is in
    // flight at a time (pool 4 + queue 10 = 14 slots) so the rejection path is
    // never touched here — that path is pinned in ExecutionEngineSelectGuardTest.

    private static final int WITNESS_ITERATIONS = 45;
    private static final long WITNESS_DEADLINE_NANOS = 5_000_000_000L;

    @Test
    void analyze_terminalStatusIsNeverObservedWithoutItsResult() throws Exception {
        when(strategy.getExplainSql(anyString())).thenReturn("EXPLAIN (FORMAT JSON) SELECT 1");
        when(stmt.executeQuery(anyString())).thenThrow(
            new SQLException("ERROR: canceling statement due to user request", "57014"));

        assertEquals(0, countTerminalReadsWithNullError(true),
            "getStatus returned a terminal status whose payload was the status-only "
                + "object: status and result were published separately (defect A).");
    }

    @Test
    void execute_terminalStatusIsNeverObservedWithoutItsResult() throws Exception {
        when(stmt.execute(anyString())).thenThrow(
            new SQLException("ERROR: canceling statement due to user request", "57014"));

        assertEquals(0, countTerminalReadsWithNullError(false),
            "getStatus returned a terminal status whose payload was the status-only "
                + "object: status and result were published separately (defect A).");
    }

    /**
     * Spins getStatus with NO sleep (the pre-fix window is between two adjacent
     * writes, so a sleeping poller almost never lands inside it) and returns how
     * many times a terminal read carried a null error().
     */
    private int countTerminalReadsWithNullError(boolean analyze) throws Exception {
        int violations = 0;
        for (int i = 0; i < WITNESS_ITERATIONS; i++) {
            String id = analyze
                ? engine.analyze("SELECT 1", "conn-1", /*timeout*/ 1)
                : engine.execute("SELECT 1", "conn-1", /*timeout*/ 1, /*maxRows*/ 100);

            long deadline = System.nanoTime() + WITNESS_DEADLINE_NANOS;
            boolean terminal = false;
            while (System.nanoTime() < deadline) {
                ExecutionResult r = engine.getStatus(id);
                if (r == null) continue;
                if (r.status() == ExecutionStatus.TIMED_OUT) {
                    if (r.error() == null) violations++;
                    terminal = true;
                    break;
                }
            }
            final int iteration = i;
            assertTrue(terminal, () -> "iteration " + iteration + ": no TIMED_OUT observed for " + id);
        }
        return violations;
    }
}
