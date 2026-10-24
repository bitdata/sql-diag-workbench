package com.bitdata.sqlworkbench.sql;

import com.bitdata.sqlworkbench.sql.decompose.RuleTestDeps;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SelectOnlyGuardTest {

    private final SelectOnlyGuard guard = new SelectOnlyGuard(RuleTestDeps.testResolver());

    @Test
    void acceptsPlainSelect() {
        assertDoesNotThrow(() -> guard.requireSingleSelect("SELECT 1", "postgresql"));
        assertDoesNotThrow(() -> guard.requireSingleSelect("SELECT * FROM users WHERE id = 1", "postgresql"));
    }

    @Test
    void acceptsWithAndSetOperations() {
        assertDoesNotThrow(() -> guard.requireSingleSelect(
            "WITH cte AS (SELECT 1 AS x) SELECT x FROM cte", "postgresql"));
        assertDoesNotThrow(() -> guard.requireSingleSelect(
            "SELECT 1 UNION SELECT 2", "postgresql"));
        assertDoesNotThrow(() -> guard.requireSingleSelect(
            "SELECT id FROM users ORDER BY id LIMIT 10", "postgresql"));
    }

    @Test
    void acceptsPgDoubleColonCast() {
        // debt-convergence-2026-09 followup 1/3 (`SelectOnlyGuard` rejected PG
        // `::` cast): the ticket's exact symptom SQL must now pass the guard
        // under the PG dialect. The guard parses via resolver.parserConfig(dialect),
        // and PostgreSqlStrategy now supplies SqlBabelParserImpl.FACTORY, so the
        // guard's parse and PgBabelParserAcceptanceTest.pgGenerateSeries_isAccepted
        // share one config. Red baseline (legacy default factory + BABEL
        // conformance): guard threw `Encountered ":" at line 1, column 9`.
        assertDoesNotThrow(() -> guard.requireSingleSelect(
            "SELECT n::int AS x, n::text AS label FROM generate_series(1,3) AS n", "postgresql"));
    }

    @Test
    void rejectsMutationStatements() {
        for (String sql : new String[]{
                "DROP TABLE users",
                "DELETE FROM users",
                "UPDATE users SET name = 'x'",
                "INSERT INTO users (name) VALUES ('x')",
                "CREATE TABLE t (id int)",
                "TRUNCATE TABLE users"}) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> guard.requireSingleSelect(sql, "postgresql"), sql);
            assertTrue(e.getMessage().contains("Only SELECT"), sql + " -> " + e.getMessage());
        }
    }

    @Test
    void rejectsMultiStatementBypass() {
        // The PG read-only transaction bypass: "SELECT 1; DROP TABLE users".
        // parseQuery() accepts exactly one statement, so the ';' fails parsing.
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> guard.requireSingleSelect("SELECT 1; DROP TABLE users", "postgresql"));
        assertTrue(e.getMessage().contains("single statement"), e.getMessage());
    }

    @Test
    void rejectsBlankAndGarbage() {
        assertThrows(IllegalArgumentException.class,
            () -> guard.requireSingleSelect("", "postgresql"));
        assertThrows(IllegalArgumentException.class,
            () -> guard.requireSingleSelect("   ", "postgresql"));
        assertThrows(IllegalArgumentException.class,
            () -> guard.requireSingleSelect("FROM WHERE SELECT", "postgresql"));
    }

    @Test
    void usesDialectParserConfig() {
        // MySQL dialect parses fine too (guard must not be PG-hardcoded)
        assertDoesNotThrow(() -> guard.requireSingleSelect("SELECT 1", "mysql"));
    }
}
