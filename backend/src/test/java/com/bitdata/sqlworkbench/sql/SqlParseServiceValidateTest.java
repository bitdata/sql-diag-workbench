package com.bitdata.sqlworkbench.sql;

import com.bitdata.sqlworkbench.connection.DatabaseStrategyFactory;
import com.bitdata.sqlworkbench.connection.MySqlStrategy;
import com.bitdata.sqlworkbench.connection.OracleStrategy;
import com.bitdata.sqlworkbench.connection.PostgreSqlStrategy;
import com.bitdata.sqlworkbench.connection.SqlServerStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** P2-12c：解析层方言白名单须与 connection 层注册表一致。 */
class SqlParseServiceValidateTest {

    private SqlDialectResolver resolver;
    private SqlParseEngine engine;

    @BeforeEach
    void setUp() {
        resolver = new SqlDialectResolver(new DatabaseStrategyFactory(Map.of(
            "postgresqlStrategy", new PostgreSqlStrategy(),
            "mysqlStrategy", new MySqlStrategy(),
            "oracleStrategy", new OracleStrategy(),
            "sqlserverStrategy", new SqlServerStrategy())));
        engine = new SqlParseEngine(resolver);
    }

    @Test
    void supportsAllRegisteredDialects() {
        assertTrue(resolver.isSupported("postgresql"));
        assertTrue(resolver.isSupported("mysql"));
        assertTrue(resolver.isSupported("oracle"));
        assertTrue(resolver.isSupported("sqlserver"));
    }

    @Test
    void rejectsUnknownDialect() {
        assertFalse(resolver.isSupported("db2"));
    }

    @Test
    void treatsNullDialectAsSupported() {
        assertTrue(resolver.isSupported(null), "null 走默认方言，不算不支持");
    }

    @Test
    void parsesOracleSmoke() throws Exception {
        assertNotNull(engine.parse("SELECT id FROM emp WHERE rownum < 10", "oracle"));
    }

    @Test
    void parsesSqlServerSmoke() throws Exception {
        // Dialect-neutral SQL: Calcite's core parser does not accept `SELECT TOP n`,
        // which is a SqlServer-specific syntax. The smoke only proves the
        // sqlserver config (quoteChar + quoting) is reachable end-to-end,
        // NOT that Calcite supports every dialect-specific feature.
        assertNotNull(engine.parse("SELECT id FROM emp WHERE id > 1", "sqlserver"));
    }
}
