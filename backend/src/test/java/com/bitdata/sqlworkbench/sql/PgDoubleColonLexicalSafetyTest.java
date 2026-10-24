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
 * Pins that Babel's `::` support does NOT bleed into string literals or
 * quoted identifiers (spec §5.3 row 3 — emit + alone pair per M2 lesson).
 *
 * Recon probe A6 confirmed `SELECT 'a::b' AS s FROM t` parses cleanly — the
 * `::` inside the string literal is not interpreted as cast syntax.
 */
class PgDoubleColonLexicalSafetyTest {

    private SqlParser.Config pg;

    @BeforeEach
    void setUp() {
        DatabaseStrategyFactory factory = new DatabaseStrategyFactory(Map.of(
            "postgresqlStrategy", new PostgreSqlStrategy()
        ));
        pg = new SqlDialectResolver(factory).parserConfig("postgresql");
    }

    @Test
    void stringLiteral_with_doubleColon_inside_doesNotTriggerCast() {
        SqlNode root = assertDoesNotThrow(() ->
            SqlParser.create("SELECT 'a::b' AS s FROM t", pg).parseQuery(),
            "String literal `::` must not trigger cast (recon probe A6)"
        );
        SqlNode item = ((SqlSelect) root).getSelectList().get(0);
        // The select item is an AS alias wrapping a literal — kind must remain AS,
        // not somehow promoted to CAST due to literal contents.
        assertEquals("AS", item.getKind().toString(),
            "String literal with `::` inside an AS alias must stay AS, not CAST");
    }

    @Test
    void quotedIdentifier_with_doubleColon_inside_parsesUnchanged() {
        assertDoesNotThrow(() ->
            SqlParser.create("SELECT \"col::x\" FROM t", pg).parseQuery(),
            "Quoted identifier containing `::` must parse unchanged (recon probe A7)"
        );
    }
}
