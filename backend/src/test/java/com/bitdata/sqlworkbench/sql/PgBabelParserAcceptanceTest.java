package com.bitdata.sqlworkbench.sql;

import com.bitdata.sqlworkbench.connection.DatabaseStrategyFactory;
import com.bitdata.sqlworkbench.connection.PostgreSqlStrategy;
import org.apache.calcite.sql.parser.SqlParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Pins the EXTRA acceptance surface Babel parser factory now provides for PG
 * beyond the {@code ::} cast (global constraint #13). Probe results (2026-09-10
 * empirical check against calcite-babel-1.37.0.jar):
 *
 * <ul>
 *   <li><b>Accepted</b>: regex operators {@code ~ ~* !~ !~*}, {@code ARRAY[...]}
 *       literal, {@code LIMIT n OFFSET m}, {@code FETCH FIRST n ROWS ONLY},
 *       {@code ILIKE}, {@code SIMILAR TO}, {@code generate_series(...)},
 *       {@code string_agg(...)}, {@code GROUP BY CUBE(...)},
 *       {@code '1.1.1.1'::inet}, {@code jsonb_path_query(...)},
 *       {@code json_extract_path(...)} — these were rejected by default parser
 *       and now pass under Babel grammar.</li>
 *   <li><b>Rejected (Babel grammar gap)</b>: {@code DISTINCT ON (a) a, b} —
 *       PG-specific but NOT in Babel grammar; stays as known limitation.
 *       {@code ARRAY[1,2]::int[]} — array-typed cast gap (already pinned in
 *       {@link SqlDialectKnownLimitationsTest}).</li>
 *   <li><b>Rejected at parseQuery level</b>: DDL like {@code DROP TABLE t} —
 *       SelectOnlyGuard remains the only DDL gate at the SQL pipeline.</li>
 * </ul>
 */
class PgBabelParserAcceptanceTest {

    private SqlParser.Config pg;

    @BeforeEach
    void setUp() {
        DatabaseStrategyFactory factory = new DatabaseStrategyFactory(Map.of(
            "postgresqlStrategy", new PostgreSqlStrategy()
        ));
        pg = new SqlDialectResolver(factory).parserConfig("postgresql");
    }

    @Test
    void pgRegexOperators_tildeFamily_isAccepted() {
        // All 4 PG regex operators — case-sensitive and -insensitive, positive + negative.
        assertDoesNotThrow(() -> SqlParser.create("SELECT * FROM t WHERE a ~ 'x'",   pg).parseQuery(), "~ match");
        assertDoesNotThrow(() -> SqlParser.create("SELECT * FROM t WHERE a ~* 'x'",  pg).parseQuery(), "~* icase match");
        assertDoesNotThrow(() -> SqlParser.create("SELECT * FROM t WHERE a !~ 'x'",  pg).parseQuery(), "!~ no-match");
        assertDoesNotThrow(() -> SqlParser.create("SELECT * FROM t WHERE a !~* 'x'", pg).parseQuery(), "!~* icase no-match");
    }

    @Test
    void pgArrayLiteral_isAccepted() {
        assertDoesNotThrow(() -> SqlParser.create("SELECT ARRAY[1,2,3] FROM t", pg).parseQuery(),
            "ARRAY[...] literal must parse under Babel");
    }

    @Test
    void pgLimitOffsetAndFetchFirst_areAccepted() {
        assertDoesNotThrow(() -> SqlParser.create("SELECT * FROM t LIMIT 5 OFFSET 10", pg).parseQuery(),
            "LIMIT n OFFSET m");
        assertDoesNotThrow(() -> SqlParser.create("SELECT * FROM t FETCH FIRST 5 ROWS ONLY", pg).parseQuery(),
            "FETCH FIRST n ROWS ONLY");
    }

    @Test
    void pgIlikeAndSimilarTo_areAccepted() {
        assertDoesNotThrow(() -> SqlParser.create("SELECT * FROM t WHERE a ILIKE 'x%'", pg).parseQuery(),
            "ILIKE");
        assertDoesNotThrow(() -> SqlParser.create("SELECT * FROM t WHERE a SIMILAR TO 'x%'", pg).parseQuery(),
            "SIMILAR TO");
    }

    @Test
    void pgGenerateSeries_isAccepted() {
        // The IT-body SQL from ExecutionEngineAnalyzeIntegrationTest relies on this.
        assertDoesNotThrow(() -> SqlParser.create("SELECT n::int AS x, n::text AS label FROM generate_series(1,3) AS n", pg).parseQuery(),
            "generate_series(1,3) AS n in FROM clause");
    }

    @Test
    void pgStringAgg_isAccepted() {
        assertDoesNotThrow(() -> SqlParser.create("SELECT string_agg(a, ',') FROM t", pg).parseQuery(),
            "string_agg(a, ',') aggregate");
    }

    @Test
    void pgGroupByCube_isAccepted() {
        assertDoesNotThrow(() -> SqlParser.create("SELECT a FROM t GROUP BY CUBE(a, b)", pg).parseQuery(),
            "GROUP BY CUBE(a, b)");
    }

    @Test
    void pgInetCast_isAccepted() {
        // Another PG-typed cast that Babel grammar recognizes (unlike ::int[] array cast).
        assertDoesNotThrow(() -> SqlParser.create("SELECT '1.1.1.1'::inet FROM t", pg).parseQuery(),
            "'ip'::inet cast");
    }

    @Test
    void pgJsonFunctions_areAccepted() {
        assertDoesNotThrow(() -> SqlParser.create("SELECT jsonb_path_query('{}', '$.a') FROM t", pg).parseQuery(),
            "jsonb_path_query");
        assertDoesNotThrow(() -> SqlParser.create("SELECT json_extract_path('{}', 'a') FROM t", pg).parseQuery(),
            "json_extract_path");
    }

    @Test
    void pgDistinctOn_remainsRejected_byBabelGrammar() {
        // Negative pin — DISTINCT ON is PG-specific but NOT in Babel grammar
        // (probe 2026-09-10: `Incorrect syntax near the keyword 'ON'`).
        // Stays as a known Babel gap; do not add a "supported" assertion.
        assertThrows(Exception.class,
            () -> SqlParser.create("SELECT DISTINCT ON (a) a, b FROM t", pg).parseQuery(),
            "DISTINCT ON stays rejected — Babel grammar gap (not a PG `::` issue)");
    }

    @Test
    void dropTable_remainsRejectedAtParserLevel() {
        // Negative pin: SelectOnlyGuard at the SQL pipeline filters DROP — but parser
        // itself should also reject it as a top-level query (parseQuery not parseStmtList).
        assertThrows(Exception.class,
            () -> SqlParser.create("DROP TABLE t", pg).parseQuery(),
            "DROP TABLE must remain rejected at parseQuery level — SelectOnlyGuard path");
    }
}