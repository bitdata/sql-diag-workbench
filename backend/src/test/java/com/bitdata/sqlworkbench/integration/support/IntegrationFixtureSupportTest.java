// backend/src/test/java/com/bitdata/sqlworkbench/integration/support/IntegrationFixtureSupportTest.java
package com.bitdata.sqlworkbench.integration.support;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static com.bitdata.sqlworkbench.integration.support.IntegrationFixtureSupport.splitStatements;
import static org.junit.jupiter.api.Assertions.*;

/**
 * DB-free unit tests: feed the actual classpath fixture SQL files through the
 * statement splitter and verify the split shape.
 */
class IntegrationFixtureSupportTest {

    private static String read(String path) throws IOException {
        try (InputStream in = IntegrationFixtureSupportTest.class.getClassLoader().getResourceAsStream(path)) {
            assertNotNull(in, "missing test resource: " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void pgSchemaYieldsTwoCreateTableStatements() throws IOException {
        List<String> stmts = splitStatements(read("integration/postgresql/schema.sql"));
        assertEquals(2, stmts.size(), () -> "got: " + stmts);
        assertTrue(stmts.stream().allMatch(s -> s.startsWith("CREATE TABLE IF NOT EXISTS")),
            () -> "expected all CREATE TABLE, got: " + stmts);
    }

    @Test
    void pgSeedYieldsTwoTruncatesAndTwoInserts() throws IOException {
        List<String> stmts = splitStatements(read("integration/postgresql/seed.sql"));
        assertEquals(4, stmts.size(), () -> "got: " + stmts);
        assertEquals(2, stmts.stream().filter(s -> s.startsWith("TRUNCATE TABLE")).count());
        assertEquals(2, stmts.stream().filter(s -> s.startsWith("INSERT INTO")).count());
    }

    @Test
    void noStatementIsBlankOrCommentLed() throws IOException {
        for (String path : List.of(
                "integration/postgresql/schema.sql", "integration/postgresql/seed.sql",
                "integration/mysql/schema.sql", "integration/mysql/seed.sql")) {
            List<String> stmts = splitStatements(read(path));
            assertFalse(stmts.isEmpty(), path);
            for (String s : stmts) {
                assertFalse(s.isBlank(), path + " yielded blank statement");
                assertFalse(s.startsWith("--"), path + " yielded comment-led statement: " + s);
            }
        }
    }

    // ---- Comment-edge cases (first live-run 2026-09-06 surfaced these) ----
    // mysql/schema.sql's second comment contains ';' mid-line:
    //   "-- Uses prefix sqldiag_it_ on user-configured database; no CREATE DATABASE (avoid extra priv)."
    // Naive split(';') cut mid-comment, producing a chunk starting with the
    // post-';' text and no '--' prefix → MySQL ate it as SQL.
    @Test
    void splitterIgnoresSemicolonInsideLineComment() {
        String sql = "-- header: uses foo; bar.\nCREATE TABLE x (id INT);\n-- trailer\n";
        List<String> stmts = splitStatements(sql);
        assertEquals(1, stmts.size(), () -> "got: " + stmts);
        assertTrue(stmts.get(0).startsWith("CREATE TABLE"), () -> "got: " + stmts.get(0));
    }

    @Test
    void splitterIgnoresMidLineCommentUntilNewline() {
        // Mid-line comment is also stripped (was a parked Minor in pre-iteration review).
        String sql = "CREATE TABLE x (id INT); -- trailing remark, mid-line\n";
        List<String> stmts = splitStatements(sql);
        assertEquals(1, stmts.size(), () -> "got: " + stmts);
        assertFalse(stmts.get(0).contains("trailing remark"),
            () -> "mid-line comment leaked into statement: " + stmts.get(0));
    }

    @Test
    void mysqlSchemaYieldsTwoCreateTableStatements() throws IOException {
        // Companion to pgSchemaYieldsTwoCreateTableStatements — would have caught
        // today's bug pre-merge; pinning both dialects so neither regresses.
        List<String> stmts = splitStatements(read("integration/mysql/schema.sql"));
        assertEquals(2, stmts.size(), () -> "got: " + stmts);
        assertTrue(stmts.stream().allMatch(s -> s.startsWith("CREATE TABLE IF NOT EXISTS")),
            () -> "expected all CREATE TABLE, got: " + stmts);
    }
}
