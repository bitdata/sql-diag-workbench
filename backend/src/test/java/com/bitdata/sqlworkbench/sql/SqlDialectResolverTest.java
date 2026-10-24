package com.bitdata.sqlworkbench.sql;

import com.bitdata.sqlworkbench.connection.DatabaseStrategyFactory;
import com.bitdata.sqlworkbench.connection.MySqlStrategy;
import com.bitdata.sqlworkbench.connection.OracleStrategy;
import com.bitdata.sqlworkbench.connection.PostgreSqlStrategy;
import com.bitdata.sqlworkbench.connection.SqlServerStrategy;
import org.apache.calcite.sql.SqlDialect;
import org.apache.calcite.sql.dialect.MysqlSqlDialect;
import org.apache.calcite.sql.dialect.PostgresqlSqlDialect;
import org.apache.calcite.sql.parser.SqlParser;
import org.apache.calcite.sql.validate.SqlConformance;
import org.apache.calcite.sql.validate.SqlConformanceEnum;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SqlDialectResolverTest {

    private SqlDialectResolver resolver;

    @BeforeEach
    void setUp() {
        DatabaseStrategyFactory factory = new DatabaseStrategyFactory(Map.of(
            "postgresqlStrategy", new PostgreSqlStrategy(),
            "mysqlStrategy", new MySqlStrategy(),
            "oracleStrategy", new OracleStrategy(),
            "sqlServerStrategy", new SqlServerStrategy()
        ));
        resolver = new SqlDialectResolver(factory);
    }

    @Test
    void getProfile_postgresql_returnsPostgresqlProfile() {
        DialectProfile profile = resolver.getProfile("postgresql");
        assertEquals("postgresql", profile.name());
        assertEquals('"', profile.quoteChar());
        assertTrue(profile.caseSensitive());
    }

    @Test
    void getProfile_mysql_returnsMysqlProfile() {
        DialectProfile profile = resolver.getProfile("mysql");
        assertEquals("mysql", profile.name());
        assertEquals('`', profile.quoteChar());
        assertFalse(profile.caseSensitive());
    }

    @Test
    void getProfile_nullDialect_fallsBackToPostgresql() {
        DialectProfile profile = resolver.getProfile(null);
        assertEquals("postgresql", profile.name());
    }

    @Test
    void getProfile_unknownDialect_fallsBackToPostgresql() {
        DialectProfile profile = resolver.getProfile("unknown");
        assertEquals("postgresql", profile.name());
    }

    @Test
    void getProfile_upperCase_normalizes() {
        DialectProfile profile = resolver.getProfile("MYSQL");
        assertEquals("mysql", profile.name());
    }

    @Test
    void getDialect_postgresql_returnsCalcitePostgres() {
        SqlDialect dialect = resolver.getDialect("postgresql");
        assertEquals(PostgresqlSqlDialect.DEFAULT, dialect);
    }

    @Test
    void getDialect_mysql_returnsCalciteMysql() {
        SqlDialect dialect = resolver.getDialect("mysql");
        assertEquals(MysqlSqlDialect.DEFAULT, dialect);
    }

    @Test
    void isCaseSensitive_mysql_returnsFalse() {
        assertFalse(resolver.isCaseSensitive("mysql"));
    }

    @Test
    void isCaseSensitive_postgresql_returnsTrue() {
        assertTrue(resolver.isCaseSensitive("postgresql"));
    }

    @Test
    void parserConfig_reflectsCaseSensitivity() {
        SqlParser.Config pgConfig = resolver.parserConfig("postgresql");
        assertTrue(pgConfig.caseSensitive());

        SqlParser.Config mysqlConfig = resolver.parserConfig("mysql");
        assertFalse(mysqlConfig.caseSensitive());
    }

    @Test
    void mysqlParserConfigUsesBackTickQuoting() {
        // PR-1 (mysql-quoting-and-ledger-correction, 2026-09-17):
        // BACK_TICK (doubled-backtick escape) replaces BACK_TICK_BACKSLASH —
        // MySQL's actual identifier escape is doubled `` ` ``, not `\`.
        assertEquals(org.apache.calcite.avatica.util.Quoting.BACK_TICK,
            resolver.parserConfig("mysql").quoting());
    }

    @Test
    void postgresqlParserConfigUsesDoubleQuoting() {
        assertEquals(org.apache.calcite.avatica.util.Quoting.DOUBLE_QUOTE,
            resolver.parserConfig("postgresql").quoting());
    }

    @Test
    void instanceDialect_postgresql_returnsPostgres() {
        SqlDialect dialect = resolver.getDialect("postgresql");
        assertEquals(PostgresqlSqlDialect.DEFAULT, dialect);
    }

    @Test
    void instanceDialect_unknown_returnsPostgresFallback() {
        SqlDialect dialect = resolver.getDialect("unknown");
        assertEquals(PostgresqlSqlDialect.DEFAULT, dialect);
    }

    // ----- conformance routing (live-channel-assumption-sweep PR-2) -----

    @Test
    void parserConfig_postgresql_usesBabelConformance() {
        // Calcite 1.37 has no POSTGRESQL enum — BABEL is the de-facto PG choice
        // (most permissive; enables PG-specific syntax extensions other than
        // `::` cast, which is a grammar-level gap — see
        // SqlDialectKnownLimitationsTest).
        SqlConformance conf = resolver.parserConfig("postgresql").conformance();
        assertEquals(SqlConformanceEnum.BABEL, conf);
    }

    @Test
    void parserConfig_mysql_usesMysql5Conformance() {
        SqlConformance conf = resolver.parserConfig("mysql").conformance();
        assertEquals(SqlConformanceEnum.MYSQL_5, conf);
    }

    @Test
    void parserConfig_oracle_usesOracle10Conformance() {
        SqlConformance conf = resolver.parserConfig("oracle").conformance();
        assertEquals(SqlConformanceEnum.ORACLE_10, conf);
    }

    @Test
    void parserConfig_sqlserver_usesSqlServer2008Conformance() {
        // Calcite 1.37 has no SQL_SERVER_2005 — SQL_SERVER_2008 is the closest.
        SqlConformance conf = resolver.parserConfig("sqlserver").conformance();
        assertEquals(SqlConformanceEnum.SQL_SERVER_2008, conf);
    }

    @Test
    void parserConfig_unknownDialect_fallsBackToPostgresqlConformance() {
        SqlConformance conf = resolver.parserConfig("unknown").conformance();
        assertEquals(SqlConformanceEnum.BABEL, conf);
    }

    // ----- factory routing (pg-cast-babel-and-ticket-sweep PR-1 Task 1.4) -----

    @Test
    void postgresql_routesToBabelParserFactory() {
        // Spec §5.3 row 1: PG dialect must use Babel parser factory.
        // Calcite's SqlParser.config() does not expose factory directly via
        // public API, so verify behaviorally: `::` cast parses successfully
        // (only Babel factory allows it — verified by recon probe A8).
        SqlParser.Config cfg = resolver.parserConfig("postgresql");
        assertDoesNotThrow(
            () -> SqlParser.create("SELECT n::int AS x FROM t", cfg).parseQuery(),
            "PG dialect must use Babel parser factory — `::` cast must parse"
        );
    }

    @Test
    void mysql_usesDefaultFactory_doesNotAcceptPgDoubleColon() {
        // A2 narrow-surface: Babel factory is PG-only; other dialects keep
        // the default factory. Negative pin proves MySQL is NOT routed
        // through Babel (would silently broaden Babel acceptance).
        SqlParser.Config cfg = resolver.parserConfig("mysql");
        assertThrows(Exception.class,
            () -> SqlParser.create("SELECT n::int FROM t", cfg).parseQuery(),
            "MySQL must NOT use Babel factory — negative pin (A2 narrow-surface)"
        );
    }

    @Test
    void oracle_usesDefaultFactory_doesNotAcceptPgDoubleColon() {
        SqlParser.Config cfg = resolver.parserConfig("oracle");
        assertThrows(Exception.class,
            () -> SqlParser.create("SELECT n::int FROM t", cfg).parseQuery(),
            "Oracle must NOT use Babel factory — negative pin (A2 narrow-surface)"
        );
    }

    @Test
    void sqlserver_usesDefaultFactory_doesNotAcceptPgDoubleColon() {
        SqlParser.Config cfg = resolver.parserConfig("sqlserver");
        assertThrows(Exception.class,
            () -> SqlParser.create("SELECT n::int FROM t", cfg).parseQuery(),
            "SqlServer must NOT use Babel factory — negative pin (A2 narrow-surface)"
        );
    }
}
