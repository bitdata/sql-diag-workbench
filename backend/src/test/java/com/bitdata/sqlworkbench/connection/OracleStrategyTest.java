package com.bitdata.sqlworkbench.connection;

import com.bitdata.sqlworkbench.sql.DialectProfile;
import org.apache.calcite.sql.dialect.OracleSqlDialect;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OracleStrategyTest {

    private OracleStrategy strategy;

    @BeforeEach
    void setUp() {
        strategy = new OracleStrategy();
    }

    @Test
    void getJdbcUrl_returnsCorrectFormat() {
        String url = strategy.getJdbcUrl("localhost", 1521, "ORCL");
        assertEquals("jdbc:oracle:thin:@//localhost:1521/ORCL", url);
    }

    @Test
    void getExplainSql_returnsExplainPlanFor() {
        String sql = "SELECT * FROM users";
        String explained = strategy.getExplainSql(sql);
        assertEquals("EXPLAIN PLAN FOR SELECT * FROM users", explained);
    }

    @Test
    void getAnalyzeSql_returnsExplainPlanFor() {
        String sql = "SELECT * FROM users";
        assertEquals("EXPLAIN PLAN FOR SELECT * FROM users",
            strategy.getAnalyzeSql(sql));
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
    void dialect_returnsOracle() {
        assertEquals("oracle", strategy.dialect());
    }

    @Test
    void getJdbcUrl_withServiceNameConnectionType_returnsServiceUrl() {
        String url = strategy.getJdbcUrl("localhost", 1521, "ORCLPDB", "SERVICE_NAME");
        assertEquals("jdbc:oracle:thin:@//localhost:1521/ORCLPDB", url);
    }

    @Test
    void getJdbcUrl_withSidConnectionType_returnsSidUrl() {
        String url = strategy.getJdbcUrl("localhost", 1521, "ORCL", "SID");
        assertEquals("jdbc:oracle:thin:@localhost:1521:ORCL", url);
    }

    @Test
    void getJdbcUrl_withNullConnectionType_fallsBackToServiceName() {
        String url = strategy.getJdbcUrl("localhost", 1521, "ORCLPDB", null);
        assertEquals("jdbc:oracle:thin:@//localhost:1521/ORCLPDB", url);
    }

    @Test
    void getDialectProfile_returnsOracleProfile() {
        DialectProfile profile = strategy.getDialectProfile();
        assertEquals("oracle", profile.name());
        assertEquals(OracleSqlDialect.DEFAULT, profile.calciteDialect());
        assertEquals('"', profile.quoteChar());
        assertTrue(profile.caseSensitive());
    }

    @Test
    void getDialectProfile_reservedKeywordsNonEmpty() {
        DialectProfile profile = strategy.getDialectProfile();
        assertFalse(profile.reservedKeywords().isEmpty());
        assertTrue(profile.reservedKeywords().contains("select"));
    }

    @Test
    void wrapVerificationSql_usesFetchFirst() {
        assertEquals(
            "SELECT COUNT(*) FROM (SELECT 1 FROM (SELECT * FROM t) _inner FETCH FIRST 1 ROWS ONLY) _verify",
            strategy.wrapVerificationSql("SELECT * FROM t"));
    }
}