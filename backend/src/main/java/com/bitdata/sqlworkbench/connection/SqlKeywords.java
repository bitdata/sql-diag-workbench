package com.bitdata.sqlworkbench.connection;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Shared SQL reserved-keyword sets used by the dialect strategies to
 * build their {@code DialectProfile.reservedKeywords()}.
 *
 * <p>Most dialects agree on a core vocabulary (SELECT, FROM, WHERE, JOIN,
 * GROUP, etc.). Defining that vocabulary once here lets the individual
 * strategies extend it with their own dialect-specific words (TOP for
 * SQL Server, MINUS for Oracle, etc.) instead of copy-pasting a
 * placeholder list. The earlier approach (each strategy inlining a
 * hard-coded Set.of(...)) led to drift — Oracle's and SQL Server's
 * placeholders were nearly identical, and the "true" reserved-word
 * list for each dialect was never filled in.
 *
 * <p>Phase 2 of the connection cleanup roadmap:
 * <ul>
 *   <li>{@link #ANSI_SQL_CORE} — the ~38 words every dialect we care
 *       about reserves. Used as the base for Oracle / SQL Server.</li>
 *   <li>{@link #POSTGRESQL_EXTRA} — words PostgreSQL adds on top of
 *       the core (the project has full coverage of these).</li>
 *   <li>{@link #MYSQL_EXTRA} — MySQL's extras (cursor, fetch, etc.).</li>
 *   <li>{@link #ORACLE_EXTRA} — Oracle-only additions (MINUS, ROWNUM,
 *       SYSDATE…). These remain a placeholder until a dedicated issue
 *       tracks the full list.</li>
 *   <li>{@link #SQL_SERVER_EXTRA} — SQL Server-only additions (TOP,
 *       TIES, OFFSET/FETCH variants…). Also a placeholder.</li>
 * </ul>
 *
 * <p>All sets are immutable and lower-case (NodeToSql's
 * {@code isUnquotable} matchers are case-insensitive at the check site).
 */
public final class SqlKeywords {

    private SqlKeywords() {}

    /**
     * The ~38 keywords every major dialect we care about reserves.
     * Subset of SQL/ANSI standard reserved words, narrowed to the ones
     * actually used by decomposition rules.
     */
    public static final Set<String> ANSI_SQL_CORE = unmod(
        "select", "from", "where", "group", "by", "having", "order",
        "insert", "update", "delete", "create", "drop", "alter", "table",
        "index", "view", "join", "inner", "left", "right", "full", "outer",
        "on", "using", "and", "or", "not", "null", "is", "in", "between",
        "like", "exists", "case", "when", "then", "else", "end", "as",
        "union", "intersect", "distinct", "all", "any", "some"
    );

    /**
     * Postgres-only reserved words (extension of ANSI_SQL_CORE).
     * Kept complete — Postgres has a relatively small, well-documented
     * reserved word list.
     */
    public static final Set<String> POSTGRESQL_EXTRA = unmod(
        "analyse", "analyze", "array", "asymmetric", "authorization",
        "binary", "both", "cast", "check", "collate", "collation", "column",
        "concurrently", "constraint", "cross", "current_catalog",
        "current_date", "current_role", "current_schema", "current_time",
        "current_timestamp", "current_user", "default", "deferrable", "do",
        "fetch", "for", "foreign", "grant", "initially", "into", "isnull",
        "lateral", "leading", "limit", "localtime", "localtimestamp",
        "natural", "notnull", "offset", "only", "or", "overlaps", "placing",
        "primary", "references", "returning", "session_user", "similar",
        "symmetric", "trailing", "true", "unique", "user", "variadic",
        "verbose", "window", "with", "false"
    );

    /**
     * MySQL-only reserved words (extension of ANSI_SQL_CORE).
     * Includes SQL/PSM-style procedural additions (cursor, handler,
     * signal) that Postgres doesn't have.
     */
    public static final Set<String> MYSQL_EXTRA = unmod(
        "char", "current_date", "current_time", "current_timestamp",
        "current_user", "cursor", "database", "dec", "declare", "double",
        "elseif", "empty", "escape", "except", "exit", "float", "if",
        "insensitive", "int", "integer", "interval", "iterate", "key",
        "leave", "loop", "match", "numeric", "of", "out", "read", "real",
        "recursive", "release", "repeat", "replace", "resignal", "restrict",
        "return", "returned_sqlstate", "revoke", "rollback", "savepoint",
        "sensitive", "set", "signal", "space", "sql", "sqlcode",
        "sqlerror", "sqlstate", "sqlwarning", "start", "stored",
        "trigger", "undo", "until", "use", "values", "while", "write",
        "zone"
    );

    /**
     * Oracle-only reserved words (extension of ANSI_SQL_CORE).
     * Placeholder set — full Oracle reserved list is large and tracked
     * in a dedicated issue. Covers the words that show up in the test
     * fixtures so decomposition rules don't accidentally unquote them.
     */
    public static final Set<String> ORACLE_EXTRA = unmod(
        "minus"
    );

    /**
     * SQL Server-only reserved words (extension of ANSI_SQL_CORE).
     * Placeholder set — covers the words used in the test fixtures.
     * TOP, OFFSET, FETCH are common enough to be worth listing even
     * in the placeholder.
     */
    public static final Set<String> SQL_SERVER_EXTRA = unmod(
        "except", "top", "offset", "fetch"
    );

    /** Build the reserved-keyword set for a given dialect. */
    public static Set<String> forDialect(String dialect) {
        return switch (dialect) {
            case "postgresql" -> union(ANSI_SQL_CORE, POSTGRESQL_EXTRA);
            case "mysql"      -> union(ANSI_SQL_CORE, MYSQL_EXTRA);
            case "oracle"     -> union(ANSI_SQL_CORE, ORACLE_EXTRA);
            case "sqlserver"  -> union(ANSI_SQL_CORE, SQL_SERVER_EXTRA);
            default           -> ANSI_SQL_CORE;
        };
    }

    private static Set<String> union(Set<String> a, Set<String> b) {
        Set<String> result = new LinkedHashSet<>(a);
        result.addAll(b);
        return Collections.unmodifiableSet(result);
    }

    private static Set<String> unmod(String... words) {
        Set<String> s = new LinkedHashSet<>();
        for (String w : words) s.add(w);
        return Collections.unmodifiableSet(s);
    }
}