package com.bitdata.sqlworkbench.execution;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.sql.SQLException;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DB-free contract tests for {@link QueryTimeoutClassifier}.
 *
 * <p>Per-request query timeout (via {@link java.sql.Statement#setQueryTimeout(int)}) does
 * not always surface as {@link java.sql.SQLTimeoutException} — PostgreSQL's JDBC driver
 * cancels the running statement and rethrows a plain {@link SQLException} carrying the
 * server's cancel-signal text (e.g. {@code "canceling statement due to user request"}).
 * ExecutionEngine and RowTraceEngine must still classify these as a timeout, not a
 * failure. The classifier recognizes each dialect's cancel-signal so callers can map to
 * their own status enum (ExecutionStatus.TIMED_OUT / TraceStatus.TIMEOUT).
 *
 * <p>This is debt-convergence Follow-up 2/3 (2026-09-09): the IT tests
 * {@code executeRespectsPerRequestTimeout} / {@code analyzeRespectsPerRequestTimeout}
 * asserted TIMED_OUT but received FAILED because the cancel-signal SQLException landed
 * in the generic FAILED branch. See {@code .scratch/debt-convergence-2026-09/}.
 */
class QueryTimeoutClassifierTest {

    // ─── PostgreSQL ───────────────────────────────────────────────────────

    @Test
    void pgCancelMessage_isTimeout() {
        assertTrue(QueryTimeoutClassifier.isTimeout(
            new SQLException("ERROR: canceling statement due to user request"), "postgresql"));
    }

    @Test
    void pgCancelSqlState_isTimeout_evenWithoutCanonicalMessage() {
        // PG cancel sqlstate 57014 (query_canceled) — the driver may surface only the
        // sqlstate if the server message is localized or stripped.
        assertTrue(QueryTimeoutClassifier.isTimeout(
            new SQLException("statement was canceled", "57014"), "postgresql"));
    }

    @Test
    void pgGenericError_isNotTimeout() {
        assertFalse(QueryTimeoutClassifier.isTimeout(
            new SQLException("ERROR: syntax error at or near \"FROM\"", "42601"), "postgresql"));
    }

    // ─── MySQL ────────────────────────────────────────────────────────────

    @Test
    void mysqlInterruptedMessage_isTimeout() {
        assertTrue(QueryTimeoutClassifier.isTimeout(
            new SQLException("Query execution was interrupted"), "mysql"));
    }

    @Test
    void mysqlInterruptedSqlState_isTimeout() {
        // MySQL ER_QUERY_INTERRUPTED vendor code 1317 → sqlstate 70100.
        assertTrue(QueryTimeoutClassifier.isTimeout(
            new SQLException("interrupted", "70100"), "mysql"));
    }

    @Test
    void mysqlGenericError_isNotTimeout() {
        assertFalse(QueryTimeoutClassifier.isTimeout(
            new SQLException("Table 'foo.bar' doesn't exist", "42S02"), "mysql"));
    }

    // ─── Oracle ───────────────────────────────────────────────────────────

    @Test
    void oracleCancelCode_isTimeout() {
        assertTrue(QueryTimeoutClassifier.isTimeout(
            new SQLException("ORA-01013: user requested cancel of current operation"), "oracle"));
    }

    // ─── Dialect-agnostic fallback (caller does not know dialect) ─────────

    /**
     * RowTraceEngine may not carry the dialect at the catch site. A null dialect must
     * still classify via the union of all dialects' cancel-signals — safe because the
     * signals are dialect-specific strings that cannot appear on another dialect's
     * connection. This avoids RowTraceEngine silently misclassifying a PG cancel as
     * ERROR (debt-convergence rule #1 same-class sweep).
     */
    static Stream<Arguments> cancelSignalsAcrossDialects() {
        return Stream.of(
            Arguments.of(new SQLException("ERROR: canceling statement due to user request")),
            Arguments.of(new SQLException("Query execution was interrupted")),
            Arguments.of(new SQLException("ORA-01013: user requested cancel of current operation"))
        );
    }

    @ParameterizedTest
    @MethodSource("cancelSignalsAcrossDialects")
    void nullDialect_unionStillClassifiesCancelAsTimeout(SQLException e) {
        assertTrue(QueryTimeoutClassifier.isTimeout(e, null),
            "null dialect should fall back to union matching; message=" + e.getMessage());
    }

    @Test
    void nullDialect_genericError_isNotTimeout() {
        assertFalse(QueryTimeoutClassifier.isTimeout(
            new SQLException("ERROR: relation does not exist"), null));
    }

    // ─── SQL Server is out of scope by design ─────────────────────────────

    /**
     * SQL Server surfaces query timeout as {@link java.sql.SQLTimeoutException}, which
     * is caught by the dedicated catch block before reaching the classifier. The
     * classifier must not fabricate a timeout from an unrelated SQL Server error.
     */
    @Test
    void sqlServerGenericError_isNotTimeout() {
        assertFalse(QueryTimeoutClassifier.isTimeout(
            new SQLException("Incorrect syntax near 'SELECT'", "S0001"), "sqlserver"));
    }
}
