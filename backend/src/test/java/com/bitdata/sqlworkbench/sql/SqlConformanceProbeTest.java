package com.bitdata.sqlworkbench.sql;

import com.bitdata.sqlworkbench.connection.DatabaseStrategyFactory;
import com.bitdata.sqlworkbench.connection.MySqlStrategy;
import com.bitdata.sqlworkbench.connection.OracleStrategy;
import com.bitdata.sqlworkbench.connection.PostgreSqlStrategy;
import com.bitdata.sqlworkbench.connection.SqlServerStrategy;
import org.apache.calcite.sql.parser.SqlParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * Live probe (RED → GREEN) for live-channel-assumption-sweep PR-2.
 *
 * <p>Verifies that {@link SqlDialectResolver#parserConfig(String)} now wires
 * {@code withConformance} per dialect, so each dialect's native syntax passes
 * Calcite parsing. The probe is DB-free (no Testcontainers, no PG connection);
 * it only exercises the Calcite parser surface, so it can run on any machine.
 *
 * <h3>Scope (post-PR-2 — what this probe covers)</h3>
 * <ul>
 *   <li>PG BABEL conformance — defensive; BABEL is the most permissive enum
 *       and the de-facto PG choice in Calcite 1.37 (no dedicated POSTGRESQL).</li>
 *   <li>MySQL {@code `backtick`} identifiers — was already working via quoting;
 *       MYSQL_5 conformance locks in MySQL extensions.</li>
 *   <li>Oracle {@code FROM dual} — was already working in DEFAULT conformance;
 *       ORACLE_10 conformance locks in Oracle extensions.</li>
 *   <li>SqlServer {@code [bracket]} identifiers — fixed by
 *       <b>SqlServerStrategy.quoteChar = '['</b> (was wrongly {@code '"'} before,
 *       which prevented {@code SELECT [id] FROM [t]} from parsing).</li>
 * </ul>
 *
 * <h3>Scope gap (out of PR-2 — documented in KnownLimitationsTest)</h3>
 * PG {@code expr::type} cast — Calcite 1.37 grammar does NOT include {@code ::}
 * as a postfix operator. Conformance layer cannot enable it; fix needs an SQL
 * preprocessor that converts {@code ::} → {@code CAST(... AS ...)}. Tracked as
 * spec deviation in
 * {@code .scratch/live-channel-assumption-sweep-design/spec-deviations.md}.
 *
 * <p>Spec anchor: docs/superpowers/specs/2026-09-09-live-channel-assumption-sweep-design.md §3 (bug ①)
 */
class SqlConformanceProbeTest {

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
    void postgresql_parserAcceptsBabelExtensions() {
        // PG default syntax (SELECT col FROM t) — would have passed before too;
        // lock it in so BABEL can't accidentally regress basic PG.
        SqlParser.Config config = resolver.parserConfig("postgresql");
        assertDoesNotThrow(() -> SqlParser.create("SELECT id FROM t WHERE x > 1", config).parseQuery(),
            "PG basic SELECT must parse under BABEL conformance");
    }

    @Test
    void mysql_parserAcceptsBackTickIdentifier() {
        SqlParser.Config config = resolver.parserConfig("mysql");
        assertDoesNotThrow(() -> SqlParser.create("SELECT `id` FROM `t`", config).parseQuery(),
            "MySQL backtick identifier must parse");
    }

    @Test
    void oracle_parserAcceptsDual() {
        SqlParser.Config config = resolver.parserConfig("oracle");
        assertDoesNotThrow(() -> SqlParser.create("SELECT 1 FROM dual", config).parseQuery(),
            "Oracle `FROM dual` must parse under ORACLE_10 conformance");
    }

    @Test
    void sqlserver_parserAcceptsBracketIdentifier() {
        // Headline fix for SqlServer channels: was failing because
        // SqlServerStrategy.quoteChar was wrongly '"', so parser received
        // Quoting.DOUBLE_QUOTE and rejected `[`. Now quoteChar='[' maps to
        // Quoting.BRACKET in SqlDialectResolver.quotingFor().
        SqlParser.Config config = resolver.parserConfig("sqlserver");
        assertDoesNotThrow(() -> SqlParser.create("SELECT [id] FROM [t]", config).parseQuery(),
            "SqlServer bracket identifier must parse");
    }
}
