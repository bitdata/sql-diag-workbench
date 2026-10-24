package com.bitdata.sqlworkbench.connection;

import com.bitdata.sqlworkbench.sql.DialectProfile;
import com.bitdata.sqlworkbench.testsupport.Dialect;
import com.bitdata.sqlworkbench.testsupport.LegacyEnvVarCompatNotice;
import com.bitdata.sqlworkbench.testsupport.RequiresTestDatabase;
import com.bitdata.sqlworkbench.testsupport.TestDatabaseResolver;
import com.bitdata.sqlworkbench.testsupport.TestDatabaseResolver.DatabaseEntry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end JDBC tests against a real PostgreSQL (pinned to PG by the
 * {@link RequiresTestDatabase} gate — the {@code @RequiresTestDatabase}
 * annotation does not support an "any one available" semantic, and the
 * per-dialect {@link PostgresJdbcIntegrationTest} /
 * {@link MySqlJdbcIntegrationTest} classes below cover MySQL specifically).
 *
 * <p>These tests are skipped by default — they require a usable PostgreSQL
 * entry in {@code test-databases.json} (single source of test credentials;
 * see {@link TestDatabaseResolver}). The gate surfaces the resolver's
 * detailed cause verbatim in the JUnit skip report.
 *
 * <p>Before task B (chore/debt-convergence-2026-09) this class was gated by
 * legacy per-dialect environment variables (a single-endpoint env var at the
 * top level, plus per-dialect env vars on the file-level classes below).
 * That legacy workflow is now migration notice only, surfaced by
 * {@link LegacyEnvVarCompatNotice#warnIfLegacyEnvVarsStillSet()}.
 *
 * <p>What this verifies (per spec task 5 — JDBC coverage):
 * <ol>
 *   <li>{@link PostgreSqlStrategy#getJdbcUrl} / {@link MySqlStrategy#getJdbcUrl}
 *       produce a URL the driver accepts and can connect to.</li>
 *   <li>{@link com.bitdata.sqlworkbench.sql.SqlKeywords#forDialect} reserved
 *       sets don't accidentally contain unreserved tokens that real
 *       SQL would use as identifiers (e.g. table/column names that
 *       happen to match a reserved keyword).</li>
 *   <li>{@link DatabaseStrategy#getExplainSql} runs without parse errors.</li>
 *   <li>{@link DatabaseStrategy#configureReadOnly} succeeds on connections
 *       that support it; doesn't throw on connections that don't.</li>
 * </ol>
 */
@RequiresTestDatabase(Dialect.POSTGRESQL)
@Tag("integration")
class JdbcIntegrationTest {

    /** Pinned by the {@code @RequiresTestDatabase(POSTGRESQL)} gate. */
    private static final String DIALECT = "postgresql";

    private static DatabaseEntry entry() {
        return TestDatabaseResolver.resolve(Dialect.POSTGRESQL);
    }
    private static String url()  { return TestDatabaseResolver.buildJdbcUrl(Dialect.POSTGRESQL, entry()); }
    private static String user() { return entry().username(); }
    private static String pass() { return entry().password(); }

    @BeforeAll
    static void warnLegacyEnv() {
        LegacyEnvVarCompatNotice.warnIfLegacyEnvVarsStillSet();
    }

    @org.junit.jupiter.api.Nested
    class ConnectAndExecute {
        @org.junit.jupiter.api.Test
        void opensConnection() throws SQLException {
            try (Connection conn = DriverManager.getConnection(url(), user(), pass())) {
                assertNotNull(conn);
                assertFalse(conn.isClosed());
            }
        }

        @org.junit.jupiter.api.Test
        void runsSampleQuery() throws SQLException {
            // Simplified sample (the 3-CTE query shape from the project
            // history needs CTE syntax which is exercised by the FullChain
            // tests; here we just confirm SELECT round-trips).
            String sql = "SELECT 1 AS one, 'hello' AS greeting";
            try (Connection conn = DriverManager.getConnection(url(), user(), pass());
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(sql)) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt("one"));
                assertEquals("hello", rs.getString("greeting"));
            }
        }
    }

    @org.junit.jupiter.api.Nested
    class ReservedKeywordsRoundtrip {
        @org.junit.jupiter.api.Test
        void reservedKeywordsDoNotEatUnquotedIdentifiers() {
            // Pick an identifier that's NOT reserved (e.g. "emp" / "salary")
            // and verify the strategy's reserved set doesn't accidentally
            // contain it. If it did, real SQL like `SELECT emp FROM ...`
            // would fail to parse.
            DialectProfile profile = dialectProfile();
            assertFalse(profile.reservedKeywords().contains("emp"),
                "Reserved set must not contain common identifier 'emp'");
            assertFalse(profile.reservedKeywords().contains("salary"),
                "Reserved set must not contain common identifier 'salary'");
            assertFalse(profile.reservedKeywords().contains("first_name"),
                "Reserved set must not contain common identifier 'first_name'");
        }
    }

    @org.junit.jupiter.api.Nested
    class ExplainSqlSmoke {
        @org.junit.jupiter.api.Test
        void explainPrefixRunsWithoutParseError() throws SQLException {
            DatabaseStrategy strategy = strategyForDialect();
            String sql = "SELECT 1";
            String explain = strategy.getExplainSql(sql);
            assertNotNull(explain);
            // Run the EXPLAIN against the live DB. Some drivers require
            // a non-read-only transaction; wrap in a try-with-resources
            // and tolerate driver-specific error messages.
            try (Connection conn = DriverManager.getConnection(url(), user(), pass());
                 Statement stmt = conn.createStatement()) {
                assertDoesNotThrow(() -> {
                    try (ResultSet rs = stmt.executeQuery(explain)) {
                        // Either rows (PG/MySQL EXPLAIN) or columns exist
                        // (Oracle/MSSQL may return column metadata only).
                        // The important thing is no SQL parse exception.
                        rs.getMetaData();
                    }
                });
            }
        }
    }

    @org.junit.jupiter.api.Nested
    class ReadOnlyToggle {
        @org.junit.jupiter.api.Test
        void configureReadOnlyDoesNotThrow() throws SQLException {
            DatabaseStrategy strategy = strategyForDialect();
            try (Connection conn = DriverManager.getConnection(url(), user(), pass())) {
                assertDoesNotThrow(() -> strategy.configureReadOnly(conn));
                // MySQL's supportsReadOnly() returns false, so the conn
                // may still report writable=true. PG/Oracle/SqlServer
                // flip the flag — verify only on dialects that claim support.
                if (strategy.supportsReadOnly()) {
                    assertTrue(conn.isReadOnly(), "Dialect " + DIALECT
                        + " claims supportsReadOnly() but conn.isReadOnly() is false");
                }
            }
        }
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private static DatabaseStrategy strategyForDialect() {
        // Top-level class is gated to POSTGRESQL — strategy is pinned.
        return new PostgreSqlStrategy();
    }

    private static DialectProfile dialectProfile() {
        return strategyForDialect().getDialectProfile();
    }
}

// ─────────────────────────────────────────────────────────────────────────
// Per-dialect JDBC smoke tests — gated by @RequiresTestDatabase (single
// credential source: test-databases.json). Before task B these were gated
// by per-dialect legacy environment variables; that legacy workflow is
// migration notice only now (LegacyEnvVarCompatNotice).
// ─────────────────────────────────────────────────────────────────────────

@RequiresTestDatabase(Dialect.POSTGRESQL)
@Tag("integration")
class PostgresJdbcIntegrationTest {

    private static DatabaseEntry entry() {
        return TestDatabaseResolver.resolve(Dialect.POSTGRESQL);
    }
    private static String url()  { return TestDatabaseResolver.buildJdbcUrl(Dialect.POSTGRESQL, entry()); }
    private static String user() { return entry().username(); }
    private static String pass() { return entry().password(); }

    @BeforeAll
    static void warnLegacyEnv() {
        LegacyEnvVarCompatNotice.warnIfLegacyEnvVarsStillSet();
    }

    @org.junit.jupiter.api.Test
    void connectsAndRunsEmployeesQuery() throws SQLException {
        // 3-table INNER JOIN + WHERE — exercises real PG grammar through
        // DriverManager (the resolver-built URL). The employees schema is
        // part of the project's pre-existing PG test fixture.
        String sql = "SELECT e.first_name, e.last_name, d.dept_name "
                   + "FROM employees e "
                   + "INNER JOIN dept_emp de ON e.emp_no = de.emp_no "
                   + "INNER JOIN departments d ON d.dept_no = de.dept_no "
                   + "WHERE e.gender = 'M' AND d.dept_name = 'Sales' "
                   + "LIMIT 5";

        try (Connection conn = DriverManager.getConnection(url(), user(), pass());
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            assertEquals(3, rs.getMetaData().getColumnCount(),
                "3-table JOIN should return 3 columns");
        }
    }

    @org.junit.jupiter.api.Test
    void cteQueryParses() throws SQLException {
        // A simplified 1-CTE query — exercises WITH ... AS (...) syntax
        // which both PG and MySQL support.
        String sql = "WITH v1 AS ( "
                   + "  SELECT e.emp_no, e.last_name FROM employees e WHERE e.gender = 'M' "
                   + ") "
                   + "SELECT v1.last_name FROM v1 LIMIT 5";

        try (Connection conn = DriverManager.getConnection(url(), user(), pass());
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            assertNotNull(rs.getMetaData());
        }
    }

    @org.junit.jupiter.api.Test
    void reservedKeywordsDontShadowColumnNames() {
        // 'name', 'user', 'order' are often reserved. Verify the
        // strategy's reserved set doesn't shadow common employees-table
        // columns when used unquoted.
        DialectProfile profile = new PostgreSqlStrategy().getDialectProfile();
        assertFalse(profile.reservedKeywords().contains("first_name"));
        assertFalse(profile.reservedKeywords().contains("last_name"));
        assertFalse(profile.reservedKeywords().contains("dept_name"));
        assertFalse(profile.reservedKeywords().contains("emp_no"));
        assertFalse(profile.reservedKeywords().contains("dept_no"));
    }

    @org.junit.jupiter.api.Test
    void explainPlanExecutes() throws SQLException {
        String sql = "SELECT 1";
        String explainSql = new PostgreSqlStrategy().getExplainSql(sql);
        try (Connection conn = DriverManager.getConnection(url(), user(), pass());
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(explainSql)) {
            assertNotNull(rs.getMetaData());
        }
    }

    @org.junit.jupiter.api.Test
    void readOnlyModeApplied() throws SQLException {
        try (Connection conn = DriverManager.getConnection(url(), user(), pass())) {
            new PostgreSqlStrategy().configureReadOnly(conn);
            assertTrue(conn.isReadOnly(),
                "PostgreSQL strategy claims read-only support — conn should be read-only");
        }
    }
}

@RequiresTestDatabase(Dialect.MYSQL)
@Tag("integration")
class MySqlJdbcIntegrationTest {

    private static DatabaseEntry entry() {
        return TestDatabaseResolver.resolve(Dialect.MYSQL);
    }
    private static String url()  { return TestDatabaseResolver.buildJdbcUrl(Dialect.MYSQL, entry()); }
    private static String user() { return entry().username(); }
    private static String pass() { return entry().password(); }

    @BeforeAll
    static void warnLegacyEnv() {
        LegacyEnvVarCompatNotice.warnIfLegacyEnvVarsStillSet();
    }

    @org.junit.jupiter.api.Test
    void connectsAndRunsEmployeesQuery() throws SQLException {
        String sql = "SELECT e.first_name, e.last_name, d.dept_name "
                   + "FROM employees e "
                   + "INNER JOIN dept_emp de ON e.emp_no = de.emp_no "
                   + "INNER JOIN departments d ON d.dept_no = de.dept_no "
                   + "WHERE e.gender = 'M' AND d.dept_name = 'Sales' "
                   + "LIMIT 5";

        try (Connection conn = DriverManager.getConnection(url(), user(), pass());
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            assertEquals(3, rs.getMetaData().getColumnCount());
        }
    }

    @org.junit.jupiter.api.Test
    void reservedKeywordsDontShadowColumnNames() {
        DialectProfile profile = new MySqlStrategy().getDialectProfile();
        assertFalse(profile.reservedKeywords().contains("first_name"));
        assertFalse(profile.reservedKeywords().contains("last_name"));
        assertFalse(profile.reservedKeywords().contains("dept_name"));
        assertFalse(profile.reservedKeywords().contains("emp_no"));
        assertFalse(profile.reservedKeywords().contains("dept_no"));
    }

    @org.junit.jupiter.api.Test
    void explainPlanExecutes() throws SQLException {
        String sql = "SELECT 1";
        String explainSql = new MySqlStrategy().getExplainSql(sql);
        try (Connection conn = DriverManager.getConnection(url(), user(), pass());
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(explainSql)) {
            assertNotNull(rs.getMetaData());
        }
    }

    @org.junit.jupiter.api.Test
    void readOnlyModeNoOp() throws SQLException {
        // MySQL strategy.supportsReadOnly() returns false — configureReadOnly
        // should be a no-op, not throw.
        try (Connection conn = DriverManager.getConnection(url(), user(), pass())) {
            assertDoesNotThrow(() -> new MySqlStrategy().configureReadOnly(conn));
        }
    }
}
