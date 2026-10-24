package com.bitdata.sqlworkbench.connection;

import com.bitdata.sqlworkbench.sql.DialectProfile;
import com.bitdata.sqlworkbench.testsupport.Dialect;
import com.bitdata.sqlworkbench.testsupport.LegacyEnvVarCompatNotice;
import com.bitdata.sqlworkbench.testsupport.RequiresTestDatabase;
import com.bitdata.sqlworkbench.testsupport.TestDatabaseResolver;
import com.bitdata.sqlworkbench.testsupport.TestDatabaseResolver.DatabaseEntry;
import org.apache.calcite.sql.dialect.OracleSqlDialect;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Live Oracle JDBC contract tests. Gated by {@link RequiresTestDatabase} —
 * credentials come from {@code test-databases.json} (single source), see
 * {@link TestDatabaseResolver}. Skips with an informative reason when no
 * usable Oracle entry is present.
 *
 * <p>Before task B (chore/debt-convergence-2026-09) this class was gated by
 * a per-dialect legacy environment variable and read user/password straight
 * from the environment; that legacy workflow is now migration notice only,
 * surfaced by {@link LegacyEnvVarCompatNotice#warnIfLegacyEnvVarsStillSet()}.
 */
@RequiresTestDatabase(Dialect.ORACLE)
@Tag("integration")
class OracleJdbcIntegrationTest {

    private static DatabaseEntry entry() {
        return TestDatabaseResolver.resolve(Dialect.ORACLE);
    }
    private static String url()  { return TestDatabaseResolver.buildJdbcUrl(Dialect.ORACLE, entry()); }
    private static String user() { return entry().username(); }
    private static String pass() { return entry().password(); }

    @BeforeAll
    static void warnLegacyEnv() {
        LegacyEnvVarCompatNotice.warnIfLegacyEnvVarsStillSet();
    }

    @org.junit.jupiter.api.Test
    void opensConnection_toOrclpdb() throws SQLException {
        try (Connection conn = DriverManager.getConnection(url(), user(), pass())) {
            assertNotNull(conn);
            assertFalse(conn.isClosed());
        }
    }

    @org.junit.jupiter.api.Test
    void runsOracleQuery_selectDual() throws SQLException {
        try (Connection conn = DriverManager.getConnection(url(), user(), pass());
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT 'hello' AS greeting FROM DUAL")) {
            assertTrue(rs.next());
            assertEquals("hello", rs.getString("greeting"));
        }
    }

    @org.junit.jupiter.api.Test
    void runsOracleQuery_withSidUrl() throws SQLException {
        // Convert SERVICE_NAME URL to SID URL form.
        // jdbc:oracle:thin:@//host:port/SERVICE → jdbc:oracle:thin:@host:port:SERVICE
        // Not all Oracle installations accept SID connections (the listener
        // may register only services). This test attempts the SID form and
        // accepts either success or a Listener-refused error — the URL
        // format itself is unit-tested in jdbcUrl_serviceNameAndSidForms.
        String svc = url();
        assertTrue(svc.contains("@//"), "Expected SERVICE_NAME URL, got: " + svc);
        String sidUrl = svc.replace("@//", "@").replaceFirst("/([^/]+)$", ":$1");
        try (Connection conn = DriverManager.getConnection(sidUrl, user(), pass())) {
            assertNotNull(conn);
            assertFalse(conn.isClosed());
        } catch (SQLException e) {
            // Listener-refused is a server config issue, not a strategy bug.
            // The SID URL format is exercised by the unit test.
            String msg = e.getMessage() == null ? "" : e.getMessage();
            assertTrue(msg.toLowerCase().contains("listener"),
                "Unexpected SQLException (not listener-related): " + msg);
        }
    }

    @org.junit.jupiter.api.Test
    void explainPlanFor_executesWithoutParseError() throws SQLException {
        OracleStrategy strategy = new OracleStrategy();
        String explained = strategy.getExplainSql("SELECT 1 FROM DUAL");
        try (Connection conn = DriverManager.getConnection(url(), user(), pass());
             Statement stmt = conn.createStatement()) {
            // EXPLAIN PLAN FOR fills plan_table, returns no rows. assertDoesNotThrow
            // covers parse / privilege / table-existence errors.
            assertDoesNotThrow(() -> stmt.execute(explained));
        }
    }

    @org.junit.jupiter.api.Test
    void readOnlyMode_applied() throws SQLException {
        try (Connection conn = DriverManager.getConnection(url(), user(), pass())) {
            new OracleStrategy().configureReadOnly(conn);
            // ALTER SESSION SET READ ONLY is session-scoped and reliably flips
            // the JDBC read-only flag on Oracle.
            assertTrue(conn.isReadOnly(),
                "Oracle claims supportsReadOnly but conn.isReadOnly is false");
        }
    }

    @org.junit.jupiter.api.Test
    void dialectProfile_usesOracleSqlDialect() {
        DialectProfile profile = new OracleStrategy().getDialectProfile();
        assertEquals(OracleSqlDialect.DEFAULT, profile.calciteDialect());
        assertEquals("oracle", profile.name());
    }

    @org.junit.jupiter.api.Test
    void jdbcUrl_serviceNameAndSidForms() {
        OracleStrategy strategy = new OracleStrategy();
        assertEquals(
            "jdbc:oracle:thin:@//localhost:1521/ORCLPDB",
            strategy.getJdbcUrl("localhost", 1521, "ORCLPDB", "SERVICE_NAME"));
        assertEquals(
            "jdbc:oracle:thin:@localhost:1521:ORCL",
            strategy.getJdbcUrl("localhost", 1521, "ORCL", "SID"));
        assertEquals(
            "jdbc:oracle:thin:@//localhost:1521/ORCLPDB",
            strategy.getJdbcUrl("localhost", 1521, "ORCLPDB", null));
    }
}
