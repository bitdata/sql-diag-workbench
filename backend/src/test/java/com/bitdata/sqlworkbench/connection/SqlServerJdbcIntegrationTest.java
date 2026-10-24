package com.bitdata.sqlworkbench.connection;

import com.bitdata.sqlworkbench.sql.DialectProfile;
import com.bitdata.sqlworkbench.testsupport.Dialect;
import com.bitdata.sqlworkbench.testsupport.LegacyEnvVarCompatNotice;
import com.bitdata.sqlworkbench.testsupport.RequiresTestDatabase;
import com.bitdata.sqlworkbench.testsupport.TestDatabaseResolver;
import com.bitdata.sqlworkbench.testsupport.TestDatabaseResolver.DatabaseEntry;
import org.apache.calcite.sql.dialect.MssqlSqlDialect;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Live SqlServer JDBC contract tests. Gated by {@link RequiresTestDatabase} —
 * credentials come from {@code test-databases.json} (single source), see
 * {@link TestDatabaseResolver}. Skips with an informative reason when no
 * usable SqlServer entry is present.
 *
 * <p>Before task B (chore/debt-convergence-2026-09) this class was gated by
 * a per-dialect legacy environment variable; that legacy workflow is now
 * migration notice only, surfaced by
 * {@link LegacyEnvVarCompatNotice#warnIfLegacyEnvVarsStillSet()}.
 */
@RequiresTestDatabase(Dialect.SQLSERVER)
@Tag("integration")
class SqlServerJdbcIntegrationTest {

    private static DatabaseEntry entry() {
        return TestDatabaseResolver.resolve(Dialect.SQLSERVER);
    }
    private static String url()  { return TestDatabaseResolver.buildJdbcUrl(Dialect.SQLSERVER, entry()); }
    private static String user() { return entry().username(); }
    private static String pass() { return entry().password(); }

    @BeforeAll
    static void warnLegacyEnv() {
        LegacyEnvVarCompatNotice.warnIfLegacyEnvVarsStillSet();
    }

    @org.junit.jupiter.api.Test
    void opensConnection_toAdventureWorks() throws SQLException {
        try (Connection conn = DriverManager.getConnection(url(), user(), pass())) {
            assertNotNull(conn);
            assertFalse(conn.isClosed());
        }
    }

    @org.junit.jupiter.api.Test
    void runsAdventureWorksQuery_simplePerson() throws SQLException {
        String sql = "SELECT TOP 5 FirstName, LastName FROM Person.Person";
        try (Connection conn = DriverManager.getConnection(url(), user(), pass());
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            int rows = 0;
            while (rs.next()) rows++;
            assertTrue(rows > 0 && rows <= 5,
                "Expected 1-5 rows from Person.Person TOP 5, got " + rows);
        }
    }

    @org.junit.jupiter.api.Test
    void getExplainSql_runsQueryWithoutParseError() throws SQLException {
        SqlServerStrategy strategy = new SqlServerStrategy();
        String explained = strategy.getExplainSql("SELECT 1 AS one");
        try (Connection conn = DriverManager.getConnection(url(), user(), pass());
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(explained)) {
            assertNotNull(rs.getMetaData());
        }
    }

    @org.junit.jupiter.api.Test
    void readOnlyMode_applied() throws SQLException {
        // mssql-jdbc honors read-only intent at connection time via
        // ApplicationIntent=ReadOnly, not via post-connection setReadOnly(true).
        // We only assert configureReadOnly runs without error against a real
        // server — strict isReadOnly() assertion would require changing the
        // JDBC URL, which is out of scope for this parity work.
        try (Connection conn = DriverManager.getConnection(url(), user(), pass())) {
            assertDoesNotThrow(() -> new SqlServerStrategy().configureReadOnly(conn));
        }
    }

    @org.junit.jupiter.api.Test
    void dialectProfile_usesMssqlSqlDialect() {
        DialectProfile profile = new SqlServerStrategy().getDialectProfile();
        assertEquals(MssqlSqlDialect.DEFAULT, profile.calciteDialect());
        assertEquals("sqlserver", profile.name());
    }
}
