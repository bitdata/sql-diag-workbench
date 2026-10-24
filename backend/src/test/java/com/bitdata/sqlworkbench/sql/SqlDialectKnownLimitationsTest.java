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
 * Documents SQL syntax that is currently <b>NOT</b> supported by the parser
 * pipeline (Calcite 1.37 + this project's dialect wiring), so future readers
 * can find the gap without rediscovering it.
 *
 * <h3>PG array-typed cast {@code ARRAY[1,2]::int[]}</h3>
 * PG array-type cast syntax is NOT in Babel's grammar either. Babel
 * accepts scalar {@code ::} casts (e.g. {@code 1::int}, {@code n::text})
 * but does not include a production for {@code ARRAY[...]::type[]}. This
 * remains a known limitation; fix path is upstream grammar (Calcite).
 *
 * <p>PG scalar {@code ::} cast was previously documented here as a limitation,
 * but pg-cast-babel-and-ticket-sweep PR-1 (2026-09-10) closes that gap by
 * routing PG through Calcite's Babel parser factory (CALCITE-2843). The
 * assertion moved to {@code postgresql_doubleColonCast_isSupported_viaBabelFactory}
 * (positive pin).
 *
 * <p>MySQL doubled-backtick identifier escape was previously documented here
 * as a limitation, but mysql-quoting-and-ledger-correction PR-1 (2026-09-17)
 * closes that gap by flipping to {@code Quoting.BACK_TICK} and realigning the
 * preprocessor. The two assertions moved to {@code SqlParseEngineTest}
 * ({@code mysqlDoubledBackTickAloneFormIsParsedAsOneIdentifier},
 * {@code mysqlDoubledBackTickQualifiedFormPreprocessorRewritesAndParses}) as
 * positive pins.
 */
class SqlDialectKnownLimitationsTest {

    private SqlDialectResolver resolver;

    @BeforeEach
    void setUp() {
        DatabaseStrategyFactory factory = new DatabaseStrategyFactory(Map.of(
            "postgresqlStrategy", new PostgreSqlStrategy()
        ));
        resolver = new SqlDialectResolver(factory);
    }

    @Test
    void postgresql_doubleColonCast_isSupported_viaBabelFactory() {
        // PR-1 closes live-channel-assumption-sweep-design spec-deviations.md #1.
        // Babel parser factory (CALCITE-2843) is now wired for PG dialect.
        // If this test fails, babel factory wiring was lost — check SqlDialectResolver
        // + PostgreSqlStrategy + DialectProfile.parserFactory.
        SqlParser.Config config = resolver.parserConfig("postgresql");
        assertDoesNotThrow(() -> SqlParser.create("SELECT 1::int AS x", config).parseQuery(),
            "PG `::` cast must parse via Babel factory — PR-1 commits this support");
    }

    @Test
    void postgresql_arrayTypeCast_isNotSupported() {
        // Spec §2.1 probe #5: `ARRAY[1,2]::int[]` is NOT in Babel's grammar either.
        // Stay as known limitation; do not fix.
        SqlParser.Config config = resolver.parserConfig("postgresql");
        assertThrows(Exception.class,
            () -> SqlParser.create("SELECT ARRAY[1,2]::int[] FROM t", config).parseQuery(),
            "PG array-typed cast remains a known Babel grammar gap");
    }
}