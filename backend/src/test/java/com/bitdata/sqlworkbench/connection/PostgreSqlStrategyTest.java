package com.bitdata.sqlworkbench.connection;

import com.bitdata.sqlworkbench.sql.DialectProfile;
import org.apache.calcite.sql.dialect.PostgresqlSqlDialect;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PostgreSqlStrategyTest {

    private PostgreSqlStrategy strategy;

    @BeforeEach
    void setUp() {
        strategy = new PostgreSqlStrategy();
    }

    @Test
    void getDialectProfile_returnsPostgresqlProfile() {
        DialectProfile profile = strategy.getDialectProfile();
        assertEquals("postgresql", profile.name());
        assertEquals(PostgresqlSqlDialect.DEFAULT, profile.calciteDialect());
        assertEquals('"', profile.quoteChar());
        assertTrue(profile.caseSensitive());
    }

    @Test
    void getDialectProfile_reservedKeywordsIncludesSelect() {
        DialectProfile profile = strategy.getDialectProfile();
        assertTrue(profile.reservedKeywords().contains("select"));
        assertTrue(profile.reservedKeywords().contains("from"));
        assertTrue(profile.reservedKeywords().contains("where"));
    }

    @Test
    void getAnalyzeSql_returnsExplainAnalyzeJson() {
        String sql = "SELECT * FROM users";
        assertEquals("EXPLAIN (ANALYZE true, FORMAT JSON) SELECT * FROM users",
            strategy.getAnalyzeSql(sql));
    }

    @Test
    void getDialectProfile_isImmutable() {
        DialectProfile profile = strategy.getDialectProfile();
        // Reserved keywords should be unmodifiable view (Set.of returns immutable)
        assertThrows(UnsupportedOperationException.class,
            () -> profile.reservedKeywords().add("evil"));
    }

    @Test
    void wrapVerificationSql_usesLimit() {
        assertEquals(
            "SELECT COUNT(*) FROM (SELECT 1 FROM (SELECT * FROM t) AS _inner LIMIT 1) AS _verify",
            strategy.wrapVerificationSql("SELECT * FROM t"));
    }
}
