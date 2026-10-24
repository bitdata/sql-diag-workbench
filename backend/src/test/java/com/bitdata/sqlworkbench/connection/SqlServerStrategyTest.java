package com.bitdata.sqlworkbench.connection;

import com.bitdata.sqlworkbench.sql.DialectProfile;
import org.apache.calcite.sql.dialect.MssqlSqlDialect;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SqlServerStrategyTest {

    private SqlServerStrategy strategy;

    @BeforeEach
    void setUp() {
        strategy = new SqlServerStrategy();
    }

    @Test
    void getJdbcUrl_returnsCorrectFormat() {
        String url = strategy.getJdbcUrl("localhost", 1433, "mydb");
        assertEquals("jdbc:sqlserver://localhost:1433;databaseName=mydb", url,
            "trustServerCertificate must default to off (S6)");
    }

    @Test
    void getJdbcUrl_withTrustServerCertificate_appendsFlag() {
        assertEquals("jdbc:sqlserver://localhost:1433;databaseName=mydb;trustServerCertificate=true",
            strategy.getJdbcUrl("localhost", 1433, "mydb", true));
        assertEquals("jdbc:sqlserver://localhost:1433;databaseName=mydb",
            strategy.getJdbcUrl("localhost", 1433, "mydb", false));
    }

    @Test
    void getExplainSql_returnsQueryAsIs() {
        String sql = "SELECT * FROM users";
        assertEquals(sql, strategy.getExplainSql(sql));
    }

    @Test
    void getAnalyzeSql_returnsQueryAsIs() {
        String sql = "SELECT * FROM users";
        assertEquals(sql, strategy.getAnalyzeSql(sql));
    }

    @Test
    void supportsReadOnly_returnsTrue() {
        assertTrue(strategy.supportsReadOnly());
    }

    @Test
    void configureReadOnly_setsConnectionReadOnly() throws SQLException {
        Connection conn = mock(Connection.class);

        strategy.configureReadOnly(conn);

        verify(conn).setReadOnly(true);
    }

    @Test
    void dialect_returnsSqlserver() {
        assertEquals("sqlserver", strategy.dialect());
    }

    @Test
    void getDialectProfile_returnsSqlServerProfile() {
        DialectProfile profile = strategy.getDialectProfile();
        assertEquals("sqlserver", profile.name());
        assertEquals(MssqlSqlDialect.DEFAULT, profile.calciteDialect());
        // T-SQL canonical quoting is `[bracket]` (was wrongly '"' before
        // PR-2 of live-channel-assumption-sweep 2026-09-09).
        assertEquals('[', profile.quoteChar());
        assertTrue(profile.caseSensitive());
    }

    @Test
    void wrapVerificationSql_usesTop() {
        assertEquals(
            "SELECT COUNT(*) FROM (SELECT TOP 1 1 FROM (SELECT * FROM t) AS _inner) AS _verify",
            strategy.wrapVerificationSql("SELECT * FROM t"));
    }
}