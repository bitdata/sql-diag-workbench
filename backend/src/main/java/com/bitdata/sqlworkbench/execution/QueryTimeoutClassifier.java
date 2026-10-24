package com.bitdata.sqlworkbench.execution;

import java.sql.SQLException;
import java.util.Locale;

/**
 * Recognizes query-timeout signals hidden inside a plain {@link SQLException}.
 *
 * <p>Per-request query timeout (via {@link java.sql.Statement#setQueryTimeout(int)})
 * does not always surface as {@link java.sql.SQLTimeoutException}. PostgreSQL's JDBC
 * driver cancels the running statement server-side and rethrows a plain
 * {@code SQLException} carrying the server's cancel-signal text
 * ({@code "canceling statement due to user request"}, sqlstate {@code 57014}); MySQL
 * surfaces {@code "Query execution was interrupted"} (sqlstate {@code 70100}); Oracle
 * surfaces {@code ORA-01013}. Left unclassified, these land in the generic
 * {@code catch (SQLException)} branch and are reported as {@link ExecutionStatus#FAILED}
 * instead of {@link ExecutionStatus#TIMED_OUT} — the debt-convergence Follow-up 2/3
 * regression (2026-09-09).
 *
 * <p>The classifier is a predicate ({@code boolean}), not status-enum-specific, so both
 * {@link ExecutionEngine} (maps to {@code TIMED_OUT}/{@code FAILED}) and
 * {@code RowTraceEngine} (maps to {@code TIMEOUT}/{@code ERROR}) can share it without
 * coupling to each other's status enum. SQL Server surfaces timeout as
 * {@link java.sql.SQLTimeoutException} and is caught by the dedicated catch block
 * before reaching this classifier, so it is intentionally out of scope.
 *
 * <p>When the caller cannot supply a dialect, the union of all dialects' cancel-signals
 * is matched — safe because the signals are dialect-specific strings that cannot appear
 * on another dialect's connection.
 */
public final class QueryTimeoutClassifier {

    private QueryTimeoutClassifier() {}

    /**
     * True when {@code e} represents a query-timeout / cancel-by-timeout signal for the
     * given dialect. A null/blank dialect falls back to the union of all dialects'
     * signals.
     */
    public static boolean isTimeout(SQLException e, String dialect) {
        if (e == null) {
            return false;
        }
        if (dialect == null || dialect.isBlank()) {
            return isPgCancel(e) || isMySqlCancel(e) || isOracleCancel(e);
        }
        switch (dialect.toLowerCase(Locale.ROOT)) {
            case "postgresql": return isPgCancel(e);
            case "mysql": return isMySqlCancel(e);
            case "oracle": return isOracleCancel(e);
            default: return false;
        }
    }

    private static boolean isPgCancel(SQLException e) {
        return "57014".equals(e.getSQLState())
            || containsIgnoreCase(e.getMessage(), "canceling statement due to user request");
    }

    private static boolean isMySqlCancel(SQLException e) {
        return "70100".equals(e.getSQLState())
            || containsIgnoreCase(e.getMessage(), "query execution was interrupted");
    }

    private static boolean isOracleCancel(SQLException e) {
        return containsIgnoreCase(e.getMessage(), "ORA-01013");
    }

    private static boolean containsIgnoreCase(String haystack, String needle) {
        return haystack != null
            && haystack.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
    }
}
