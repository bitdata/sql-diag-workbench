package com.bitdata.sqlworkbench.sql;

import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.parser.SqlParseException;
import org.apache.calcite.sql.parser.SqlParser;
import org.springframework.stereotype.Component;

/**
 * S2 — pre-execution statement whitelist. Every execution surface validates
 * user SQL through this guard BEFORE any JDBC connection is opened: exactly
 * one statement, and it must be a read query (SELECT, incl. WITH / set ops /
 * ORDER BY wrappers).
 *
 * <p>Side effect: kills the PostgreSQL multi-statement bypass of
 * default_transaction_read_only ("SELECT 1; DROP TABLE users"), because
 * parseQuery() accepts exactly one statement.
 *
 * <p>Parses with the connection strategy's dialect config (case sensitivity),
 * per spec §2.3 — the execution path uses the strategy dialect directly and
 * does NOT touch the SqlParseService.validate endpoint.
 */
@Component
public class SelectOnlyGuard {

    private final SqlDialectResolver resolver;

    public SelectOnlyGuard(SqlDialectResolver resolver) {
        this.resolver = resolver;
    }

    /**
     * @throws IllegalArgumentException if sql is blank, does not parse as a
     *         single statement under the dialect, or is not SELECT-like.
     */
    public void requireSingleSelect(String sql, String dialect) {
        if (sql == null || sql.isBlank()) {
            throw new IllegalArgumentException("SQL must not be empty");
        }
        SqlNode node;
        try {
            // parseQuery() accepts exactly one statement: a second statement
            // ("SELECT 1; DROP TABLE users") fails parsing here. Semicolons
            // inside string literals or comments parse fine and are allowed.
            SqlParser.Config config = resolver.parserConfig(dialect);
            node = SqlParser.create(sql, config).parseQuery();
        } catch (SqlParseException e) {
            // Covers both the multi-statement bypass and statements the
            // default grammar does not parse (e.g. DROP/CREATE TABLE);
            // message carries both substrings callers assert on.
            throw new IllegalArgumentException(
                "Only SELECT statements are allowed: SQL does not parse as a single statement ("
                    + e.getMessage() + ")");
        }
        if (!isSelectLike(node.getKind())) {
            throw new IllegalArgumentException(
                "Only SELECT statements are allowed (got " + node.getKind() + ")");
        }
    }

    private boolean isSelectLike(SqlKind kind) {
        return switch (kind) {
            case SELECT, UNION, INTERSECT, EXCEPT, ORDER_BY, WITH -> true;
            default -> false;
        };
    }
}
