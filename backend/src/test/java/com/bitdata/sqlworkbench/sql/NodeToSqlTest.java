package com.bitdata.sqlworkbench.sql;

import com.bitdata.sqlworkbench.connection.MySqlStrategy;
import com.bitdata.sqlworkbench.connection.PostgreSqlStrategy;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.dialect.PostgresqlSqlDialect;
import org.apache.calcite.sql.parser.SqlParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NodeToSqlTest {

    private final PostgreSqlStrategy pgStrategy = new PostgreSqlStrategy();
    private final MySqlStrategy mysqlStrategy = new MySqlStrategy();

    private SqlNode parse(String sql) throws Exception {
        return SqlParser.create(sql).parseQuery();
    }

    @Test
    void toSql_postgresql_unquotedIdentifier() throws Exception {
        SqlNode node = parse("SELECT id FROM users");
        DialectProfile profile = pgStrategy.getDialectProfile();
        String sql = NodeToSql.toSql(node, profile);
        // PostgreSQL should not over-quote
        assertTrue(sql.toLowerCase().contains("users"));
        // No unnecessary double quotes on simple identifier
        assertFalse(sql.contains("\"users\""), "Should not over-quote 'users' in PostgreSQL: " + sql);
    }

    @Test
    void toSql_postgresql_preservesReservedWordQuoting() throws Exception {
        SqlNode node = parse("SELECT \"user\" FROM accounts");
        DialectProfile profile = pgStrategy.getDialectProfile();
        String sql = NodeToSql.toSql(node, profile);
        // 'user' is reserved, quotes preserved
        assertTrue(sql.contains("\"user\""), "Should preserve quotes on reserved word: " + sql);
    }

    @Test
    void toSql_mysql_usesBacktickQuotes() throws Exception {
        SqlNode node = parse("SELECT id FROM users");
        DialectProfile profile = mysqlStrategy.getDialectProfile();
        String sql = NodeToSql.toSql(node, profile);
        // MySQL should use backticks (Calcite uppercases unquoted identifiers)
        // Either: contains `USERS` (quoted) or no quote needed (already uppercase)
        assertTrue(sql.toUpperCase().contains("USERS"),
            "Should reference users (case-insensitive): " + sql);
        // The MySQL profile's quote char is backtick; if quoting happens, it should be backtick
        // Verify by ensuring NO double quotes appear (which would indicate PostgreSQL quoting leaked)
        assertFalse(sql.contains("\""),
            "MySQL output should not use PostgreSQL double quotes: " + sql);
    }

    @Test
    void toSql_mysql_preservesReservedWordQuoting() throws Exception {
        // Use double quotes in source (Calcite accepts those), expect MySQL to convert to backticks
        SqlNode node = parse("SELECT \"select\" FROM accounts");
        DialectProfile profile = mysqlStrategy.getDialectProfile();
        String sql = NodeToSql.toSql(node, profile);
        // 'select' is reserved; should be quoted with MySQL backticks
        assertTrue(sql.contains("`select`"),
            "Should preserve backticks on reserved word: " + sql);
    }

    @Test
    void toSql_doesNotMutateNode() throws Exception {
        SqlNode node = parse("SELECT id FROM users");
        DialectProfile profile = pgStrategy.getDialectProfile();
        String before = node.toSqlString(PostgresqlSqlDialect.DEFAULT).getSql();
        NodeToSql.toSql(node, profile);
        String after = node.toSqlString(PostgresqlSqlDialect.DEFAULT).getSql();
        assertEquals(before, after, "toSql must not mutate the SqlNode");
    }

    @Test
    void stringLiteralWithEmbeddedDoubleQuotesIsVerbatim() throws Exception {
        SqlNode node = parse("SELECT 'the \"big\" table' FROM t");
        String sql = NodeToSql.toSql(node, pgStrategy.getDialectProfile());
        assertTrue(sql.contains("'the \"big\" table'"),
            "Literal content must not be touched by identifier unquoting: " + sql);
    }

    @Test
    void stringLiteralWithEscapedQuoteIsVerbatim() throws Exception {
        SqlNode node = parse("SELECT 'it''s here' FROM t");
        String sql = NodeToSql.toSql(node, pgStrategy.getDialectProfile());
        assertTrue(sql.contains("'it''s here'"), sql);
    }

    @Test
    void mixedCaseIdentifierKeepsQuotes() throws Exception {
        SqlNode node = parse("SELECT \"UserId\" FROM t");
        String sql = NodeToSql.toSql(node, pgStrategy.getDialectProfile());
        assertTrue(sql.contains("\"UserId\""),
            "Mixed-case identifiers must keep quotes or they fold to a different column: " + sql);
    }

    @Test
    void singleCaseIdentifierIsUnquoted() throws Exception {
        SqlNode node = parse("SELECT \"userid\" FROM t");
        String sql = NodeToSql.toSql(node, pgStrategy.getDialectProfile());
        assertFalse(sql.contains("\"userid\""), sql);
    }

    @Test
    void mysqlMixedCaseIdentifierKeepsBackticks() throws Exception {
        // Default Lex parses double quotes; MySQL profile must re-emit backticks
        SqlNode node = parse("SELECT \"UserId\" FROM t");
        String sql = NodeToSql.toSql(node, mysqlStrategy.getDialectProfile());
        assertTrue(sql.contains("`UserId`"), sql);
    }
}
