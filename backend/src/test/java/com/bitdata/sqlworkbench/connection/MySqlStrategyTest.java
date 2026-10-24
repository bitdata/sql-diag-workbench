package com.bitdata.sqlworkbench.connection;

import com.bitdata.sqlworkbench.sql.DialectProfile;
import org.apache.calcite.sql.dialect.MysqlSqlDialect;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MySqlStrategyTest {

    private MySqlStrategy strategy;

    @BeforeEach
    void setUp() {
        strategy = new MySqlStrategy();
    }

    @Test
    void getDialectProfile_returnsMysqlProfile() {
        DialectProfile profile = strategy.getDialectProfile();
        assertEquals("mysql", profile.name());
        assertEquals(MysqlSqlDialect.DEFAULT, profile.calciteDialect());
        assertEquals('`', profile.quoteChar());
        assertFalse(profile.caseSensitive());
    }

    @Test
    void getExplainSql_returnsExplainFormatJson() {
        String sql = "SELECT * FROM users";
        assertEquals("EXPLAIN FORMAT=JSON SELECT * FROM users",
            strategy.getExplainSql(sql));
    }

    @Test
    void getAnalyzeSql_returnsExplainAnalyzeFormatJson() {
        String sql = "SELECT * FROM users";
        assertEquals("EXPLAIN ANALYZE FORMAT=JSON SELECT * FROM users",
            strategy.getAnalyzeSql(sql));
    }

    @Test
    void getDialectProfile_reservedKeywordsIncludesSelect() {
        DialectProfile profile = strategy.getDialectProfile();
        assertTrue(profile.reservedKeywords().contains("select"));
        assertTrue(profile.reservedKeywords().contains("from"));
    }

    @Test
    void supportsReadOnly_returnsTrue() {
        assertTrue(strategy.supportsReadOnly());
    }

    @Test
    void configureReadOnly_setsSessionReadOnlyAndJdbcFlag() throws SQLException {
        Connection conn = mock(Connection.class);
        Statement stmt = mock(Statement.class);
        when(conn.createStatement()).thenReturn(stmt);

        strategy.configureReadOnly(conn);

        verify(stmt).execute("SET SESSION TRANSACTION READ ONLY");
        verify(conn).setReadOnly(true);
    }

    @Test
    void wrapVerificationSql_usesLimit() {
        assertEquals(
            "SELECT COUNT(*) FROM (SELECT 1 FROM (SELECT * FROM t) AS _inner LIMIT 1) AS _verify",
            strategy.wrapVerificationSql("SELECT * FROM t"));
    }
}
