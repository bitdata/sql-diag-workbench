package com.bitdata.sqlworkbench.execution;

import com.bitdata.sqlworkbench.connection.ConnectionConfig;
import com.bitdata.sqlworkbench.connection.ConnectionService;
import com.bitdata.sqlworkbench.connection.DatabaseStrategy;
import com.bitdata.sqlworkbench.connection.DatabaseStrategyFactory;
import com.bitdata.sqlworkbench.execution.model.ExecutionResult;
import com.bitdata.sqlworkbench.execution.model.ExecutionStatus;
import com.bitdata.sqlworkbench.sql.SelectOnlyGuard;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * T6 probe: deterministically drives each of the three worker-side CANCELLED
 * checkpoints (spec §4.3 (B) remaining sites) and pins the phase reason each
 * one publishes.
 *
 * <p>Why this exists instead of relying on the end-to-end IT: the
 * {@code pg_sleep(30)} IT is won by the {@code cancel()} entry site (Task 5
 * already publishes CANCELLED with a reason there; the worker only reaches a
 * checkpoint after a cancel round-trip). It therefore cannot distinguish
 * "the entry site published" from "a worker checkpoint published" and is green
 * before Task 6. This probe reaches each checkpoint directly on the calling
 * thread — no race, no live database — by injecting the cancel flag or a stub
 * {@link ResultSet} at the exact point the checkpoint inspects it.
 *
 * <p>Three distinct phase strings ⇒ a failure names which checkpoint regressed.
 * On the pre-T6 implementation all three publish {@code (CANCELLED, null)}, so
 * the result assertions here are red; see task-6 report for the captured
 * {@code expected/was}.
 *
 * <p>Reflection is used only to reach the private nested {@code ExecutionContext}
 * and the private {@code runSelect}/{@code runAnalyze} methods, mirroring
 * {@link ExecutionEngineTerminalLatchTest}'s established technique.
 */
class ExecutionEngineWorkerCancelCheckpointTest {

    private static final String CONNECTION_ID = "probe-conn";
    private static final String DIALECT = "postgresql";

    private static final String PHASE_BEFORE_EXECUTION = "Cancelled before execution started";
    private static final String PHASE_FETCHING_ROWS = "Cancelled while fetching rows";
    private static final String PHASE_BEFORE_EXPLAIN = "Cancelled before EXPLAIN started";

    private final ConnectionService connectionService = mock(ConnectionService.class);
    private final ExplainServiceFactory explainServiceFactory = mock(ExplainServiceFactory.class);
    private final DatabaseStrategyFactory strategyFactory = mock(DatabaseStrategyFactory.class);
    private final SelectOnlyGuard selectOnlyGuard = mock(SelectOnlyGuard.class);
    private final DatabaseStrategy strategy = mock(DatabaseStrategy.class);

    private ExecutionEngine engine;

    @BeforeEach
    void wireDeps() throws Exception {
        // lookupDialect() resolves the connection to its dialect, then the
        // strategy factory yields the strategy runSelect/runAnalyze consult.
        ConnectionConfig config = new ConnectionConfig(
            CONNECTION_ID, "probe", "host", 5432, "db", "user", DIALECT,
            "keychain-ref", true, "2026-10-03", "2026-10-03", null, false);
        when(connectionService.getById(CONNECTION_ID)).thenReturn(Optional.of(config));
        when(strategyFactory.get(DIALECT)).thenReturn(strategy);
        when(strategy.supportsReadOnly()).thenReturn(false);
        when(explainServiceFactory.supports("postgresql")).thenReturn(true);

        // selectOnlyGuard.requireSingleSelect is void; the default mock no-op
        // lets the path pass the guard without Calcite parsing.
        engine = new ExecutionEngine(
            connectionService, explainServiceFactory, strategyFactory, selectOnlyGuard, 30, 100);
    }

    // ─── checkpoint 1 — runSelect, before stmt.execute ──────────────────────

    @Test
    void runSelectBeforeExecutionPublishesItsPhaseReason() throws Exception {
        Connection conn = mock(Connection.class);
        Statement stmt = mock(Statement.class);
        when(connectionService.openJdbcConnection(CONNECTION_ID)).thenReturn(conn);
        var metadata=mock(java.sql.DatabaseMetaData.class);
        when(conn.getMetaData()).thenReturn(metadata);
        when(metadata.getDatabaseProductName()).thenReturn("PostgreSQL");
        when(metadata.getDatabaseProductVersion()).thenReturn("16");
        when(conn.createStatement()).thenReturn(stmt);

        Object ctx = newContext("probe-before-exec", "EXECUTE", "SELECT 1");
        // Arrive at the checkpoint already cancelled.
        setCancelled(ctx, true);

        invokePrivate("runSelect", ctx);

        ExecutionResult published = publishedResult(ctx);
        // Target assertion first (NPE-safe) so the pre-T6 failure reads
        // `expected: "Cancelled before execution started" but was: <null>`.
        assertEquals(PHASE_BEFORE_EXECUTION, published == null ? null : published.error(),
            "checkpoint 1 must carry its own phase reason, not (CANCELLED, null)");
        assertNotNull(published,
            "worker checkpoint must publish a full terminal result, not (CANCELLED, null)");
        assertEquals(ExecutionStatus.CANCELLED, publishedStatus(ctx));
        verify(stmt, never()).execute(anyString());
    }

    // ─── checkpoint 3 — runAnalyze, before explainSql is built ──────────────

    @Test
    void runAnalyzeBeforeExplainPublishesItsPhaseReason() throws Exception {
        Connection conn = mock(Connection.class);
        Statement stmt = mock(Statement.class);
        when(connectionService.openJdbcConnection(CONNECTION_ID)).thenReturn(conn);
        var metadata=mock(java.sql.DatabaseMetaData.class);
        when(conn.getMetaData()).thenReturn(metadata);
        when(metadata.getDatabaseProductName()).thenReturn("PostgreSQL");
        when(metadata.getDatabaseProductVersion()).thenReturn("16");
        when(conn.createStatement()).thenReturn(stmt);

        Object ctx = newContext("probe-before-explain", "ANALYZE", "SELECT 1");
        setCancelled(ctx, true);

        invokePrivate("runAnalyze", ctx);

        ExecutionResult published = publishedResult(ctx);
        assertEquals(PHASE_BEFORE_EXPLAIN, published == null ? null : published.error(),
            "checkpoint 3 must carry its own phase reason, not (CANCELLED, null)");
        assertNotNull(published,
            "worker checkpoint must publish a full terminal result, not (CANCELLED, null)");
        assertEquals(ExecutionStatus.CANCELLED, publishedStatus(ctx));
        verify(stmt, never()).executeQuery(anyString());
    }

    // ─── checkpoint 2 — runSelect, inside the row-fetch loop ────────────────

    @Test
    void runSelectFetchLoopPublishesItsPhaseReason() throws Exception {
        Connection conn = mock(Connection.class);
        Statement stmt = mock(Statement.class);
        ResultSet rs = mock(ResultSet.class);
        ResultSetMetaData meta = mock(ResultSetMetaData.class);

        when(connectionService.openJdbcConnection(CONNECTION_ID)).thenReturn(conn);
        var metadata=mock(java.sql.DatabaseMetaData.class);
        when(conn.getMetaData()).thenReturn(metadata);
        when(metadata.getDatabaseProductName()).thenReturn("PostgreSQL");
        when(metadata.getDatabaseProductVersion()).thenReturn("16");
        when(conn.createStatement()).thenReturn(stmt);
        when(stmt.execute(anyString())).thenReturn(true);
        when(stmt.getResultSet()).thenReturn(rs);
        when(rs.getMetaData()).thenReturn(meta);
        when(meta.getColumnCount()).thenReturn(1);
        when(meta.getColumnName(1)).thenReturn("x");
        when(meta.getColumnTypeName(1)).thenReturn("int4");

        Object ctx = newContext("probe-fetch-loop", "EXECUTE", "SELECT 1");
        // Not cancelled yet: the path must clear checkpoint 1 (before execute)
        // and only become cancelled once the fetch loop is running.
        setCancelled(ctx, false);

        // The stub flips the flag as rows start arriving, so the loop's own
        // checkpoint — not the pre-execute one — is what fires.
        when(rs.next()).thenAnswer(invocation -> {
            setCancelled(ctx, true);
            return true;
        });

        invokePrivate("runSelect", ctx);

        ExecutionResult published = publishedResult(ctx);
        assertEquals(PHASE_FETCHING_ROWS, published == null ? null : published.error(),
            "checkpoint 2 must carry its own phase reason, not (CANCELLED, null)");
        assertNotNull(published,
            "worker checkpoint must publish a full terminal result, not (CANCELLED, null)");
        assertEquals(ExecutionStatus.CANCELLED, publishedStatus(ctx));
    }

    // ─── reflection helpers ─────────────────────────────────────────────────

    private static Object newContext(String executionId, String kindName, String sql) throws Exception {
        Class<?> kindClass = Class.forName("com.bitdata.sqlworkbench.execution.ExecutionEngine$Kind");
        @SuppressWarnings({"unchecked", "rawtypes"})
        Object kind = Enum.valueOf((Class<? extends Enum>) kindClass, kindName);
        Class<?> ctxClass = Class.forName("com.bitdata.sqlworkbench.execution.ExecutionEngine$ExecutionContext");
        Constructor<?> ctor = ctxClass.getDeclaredConstructor(
            String.class, String.class, String.class, kindClass, String.class);
        ctor.setAccessible(true);
        Object ctx = ctor.newInstance(executionId, sql, CONNECTION_ID, kind, null);
        setField(ctx, "effectiveTimeoutSeconds", 30);
        setField(ctx, "effectiveMaxRows", 100);
        return ctx;
    }

    private void invokePrivate(String name, Object ctx) throws Exception {
        Method m = ExecutionEngine.class.getDeclaredMethod(name, ctx.getClass());
        m.setAccessible(true);
        m.invoke(engine, ctx);
    }

    private static void setCancelled(Object ctx, boolean value) throws Exception {
        setField(ctx, "cancelled", value);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static Object publishedOf(Object ctx) throws Exception {
        Field f = ctx.getClass().getDeclaredField("published");
        f.setAccessible(true);
        return f.get(ctx);
    }

    private static ExecutionStatus publishedStatus(Object ctx) throws Exception {
        Object published = publishedOf(ctx);
        Method status = published.getClass().getDeclaredMethod("status");
        status.setAccessible(true);
        return (ExecutionStatus) status.invoke(published);
    }

    private static ExecutionResult publishedResult(Object ctx) throws Exception {
        Object published = publishedOf(ctx);
        Method result = published.getClass().getDeclaredMethod("result");
        result.setAccessible(true);
        return (ExecutionResult) result.invoke(published);
    }
}
