// backend/src/test/java/com/bitdata/sqlworkbench/integration/support/IntegrationFixtureSupport.java
package com.bitdata.sqlworkbench.integration.support;

import com.bitdata.sqlworkbench.execution.ExecutionEngine;
import com.bitdata.sqlworkbench.execution.model.ExecutionResult;
import com.bitdata.sqlworkbench.execution.model.ExecutionStatus;
import com.bitdata.sqlworkbench.testsupport.Dialect;
import com.bitdata.sqlworkbench.testsupport.TestDatabaseResolver;
import com.bitdata.sqlworkbench.testsupport.TestDatabaseResolver.DatabaseEntry;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Bare-JDBC fixture helpers — runs DDL/seed before the suite and tears down
 * afterwards. Bypasses ExecutionEngine (which has SELECT-only guard).
 */
public final class IntegrationFixtureSupport {

    private IntegrationFixtureSupport() {}

    public record Connection(DatabaseEntry entry, String jdbcUrl) {}

    public static Connection connect(Dialect dialect) {
        DatabaseEntry e = TestDatabaseResolver.resolve(dialect);
        String url = TestDatabaseResolver.buildJdbcUrl(dialect, e);
        return new Connection(e, url);
    }

    /** Run schema.sql then seed.sql. Idempotent. */
    public static void applyFixture(Dialect dialect, Connection c) throws IOException, SQLException {
        String d = dialect.name().toLowerCase();
        try (java.sql.Connection conn = DriverManager.getConnection(c.jdbcUrl(), c.entry().username(), c.entry().password());
             Statement st = conn.createStatement()) {
            for (String script : List.of("schema.sql", "seed.sql")) {
                String sql = readResource("integration/" + d + "/" + script);
                for (String stmt : splitStatements(sql)) {
                    if (!stmt.isBlank()) st.execute(stmt);
                }
            }
        }
    }

    /** DROP all fixture tables — for @AfterAll cleanup if needed. */
    public static void dropFixture(Dialect dialect, Connection c) throws SQLException {
        try (java.sql.Connection conn = DriverManager.getConnection(c.jdbcUrl(), c.entry().username(), c.entry().password());
             Statement st = conn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS sqldiag_it_orders");
            st.execute("DROP TABLE IF EXISTS sqldiag_it_customers");
        }
    }

    /**
     * Poll {@code engine.getStatus(execId)} until it reaches a terminal state
     * (COMPLETED / FAILED / TIMED_OUT / CANCELLED) or 20s elapse. Returns the
     * terminal result; throws AssertionError on timeout. Shared by all 4 live
     * IT classes (FullChain PG/MySQL, PlanBasedRules PG/MySQL) — previously
     * copied verbatim into each (Task review M-1 / debt-convergence-2026-09-09).
     *
     * <p>{@code label} customizes the timeout failure message so callers can
     * distinguish "execution did not finish" from "analyze did not finish".
     */
    public static ExecutionResult pollExecutionTerminal(
            ExecutionEngine engine, String execId, String label) throws InterruptedException {
        for (int i = 0; i < 200; i++) {  // up to 20s @ 100ms cadence
            ExecutionResult r = engine.getStatus(execId);
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
        throw new AssertionError(label + " did not finish in 20s: " + execId);
    }

    private static String readResource(String path) throws IOException {
        try (InputStream in = IntegrationFixtureSupport.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) throw new IOException("missing classpath resource: " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Naive splitter — adequate for our hand-written DDL/DML (no $$ blocks, no
     * triggers, no string literals containing '--'). Strips every {@code --}-to-EOL
     * run BEFORE splitting on {@code ;} (live-run hit 2026-09-06: a ';' inside a
     * comment otherwise cut mid-comment and the next chunk started with the
     * post-';' text — JDBC saw that fragment as SQL).
     * Package-private for unit testing.
     */
    static List<String> splitStatements(String sql) {
        String stripped = sql.replaceAll("--[^\\n]*", "");
        return Arrays.stream(stripped.split(";"))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .collect(Collectors.toList());
    }
}
