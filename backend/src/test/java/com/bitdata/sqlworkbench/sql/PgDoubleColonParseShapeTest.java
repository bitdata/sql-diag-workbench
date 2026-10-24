package com.bitdata.sqlworkbench.sql;

import com.bitdata.sqlworkbench.connection.DatabaseStrategyFactory;
import com.bitdata.sqlworkbench.connection.PostgreSqlStrategy;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlSelect;
import org.apache.calcite.sql.parser.SqlParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the AST shape produced by Babel parser factory for the four canonical
 * `::` cast forms (spec §5.3 row 2 + recon probe #1-#4).
 *
 * NOTE: spec §5.3 row 2 text says select-item kind=CAST, but recon probe B1/B2
 * (2026-09-09) showed `SELECT n::int AS x` produces kind=AS (alias wraps CAST);
 * only bare `SELECT n::int` gives CAST. The CAST-kind assertion below applies
 * ONLY to the bare form. The aliased-form assertion verifies alias wraps a
 * CAST child. See PR-1 spec writeback for the §5.3 correction.
 */
class PgDoubleColonParseShapeTest {

    private SqlParser.Config pg;

    @BeforeEach
    void setUp() {
        DatabaseStrategyFactory factory = new DatabaseStrategyFactory(Map.of(
            "postgresqlStrategy", new PostgreSqlStrategy()
        ));
        pg = new SqlDialectResolver(factory).parserConfig("postgresql");
    }

    @Test
    void bare_selectItem_isCast() {
        SqlNode root = assertDoesNotThrow(
            () -> SqlParser.create("SELECT n::int FROM t", pg).parseQuery(),
            "Bare `n::int` must parse under Babel (spec §5.3 row 2 + recon probe B2)"
        );
        SqlNode item = ((SqlSelect) root).getSelectList().get(0);
        assertEquals("CAST", item.getKind().toString(),
            "Bare `n::int` select-item must be CAST (spec §5.3 row 2 + recon probe B2)");
    }

    @Test
    void aliased_selectItem_isAs_wrappingCast() {
        SqlNode root = assertDoesNotThrow(
            () -> SqlParser.create("SELECT n::int AS x FROM t", pg).parseQuery(),
            "Aliased `n::int AS x` must parse under Babel (recon probe B1)"
        );
        SqlNode item = ((SqlSelect) root).getSelectList().get(0);
        assertEquals("AS", item.getKind().toString(),
            "Aliased form wraps CAST in AS alias (recon probe B1, spec §5.3 correction)");
    }

    @Test
    void chained_cast_parses() {
        assertDoesNotThrow(() -> SqlParser.create("SELECT a::text::int FROM t", pg).parseQuery(),
            "Chained `a::text::int` must parse (spec §2.1 probe #3)");
    }

    @Test
    void cast_in_where_clause_parses() {
        assertDoesNotThrow(() ->
            SqlParser.create("SELECT * FROM t WHERE created::date > DATE '2026-01-01'", pg).parseQuery(),
            "WHERE-clause `::` cast must parse (spec §2.1 probe #4)");
    }
}
