package com.bitdata.sqlworkbench.connection;

import java.util.List;

/**
 * Single source of truth for built-in {@link DatabaseStrategy} instances.
 *
 * <p>Previously the dialect registry was assembled in three places (Spring
 * auto-collection via {@link DatabaseStrategyFactory#DatabaseStrategyFactory},
 * the static fallback holder
 * {@link com.bitdata.sqlworkbench.sql.DialectParserConfigs}, and the test-only
 * {@link com.bitdata.sqlworkbench.sql.decompose.RuleTestDeps}). Adding a 5th
 * dialect required synchronising all three sites; missing one caused the
 * canonicalize chain to silently fall back to PostgreSQL — a violation of
 * rule #2 ("Observable degradation"). This class centralises the list of
 * built-in strategies; all assembly paths now derive from it.
 *
 * <p>The class is a static, immutable list — pure data, no behaviour — so
 * that the test-side consistency pin
 * ({@link com.bitdata.sqlworkbench.connection.BuiltInStrategiesConsistencyTest})
 * can compare bean-derived dialects against this list directly. New dialects
 * MUST be added here, not in {@link DatabaseStrategyFactory}, the static
 * holder, or {@code RuleTestDeps}.
 */
public final class BuiltInStrategies {

    /**
     * The canonical list of built-in strategies. Order is irrelevant; the
     * {@link DatabaseStrategyFactory} performs a case-insensitive lookup.
     */
    public static final List<DatabaseStrategy> ALL = List.of(
        new PostgreSqlStrategy(),
        new MySqlStrategy(),
        new OracleStrategy(),
        new SqlServerStrategy()
    );

    private BuiltInStrategies() {}

    /**
     * Returns the {@link DatabaseStrategy} whose {@link DatabaseStrategy#dialect()}
     * matches {@code dialect} (case-insensitive), or {@code null} if absent.
     */
    public static DatabaseStrategy findByDialect(String dialect) {
        if (dialect == null) {
            return null;
        }
        String lower = dialect.toLowerCase();
        for (DatabaseStrategy s : ALL) {
            if (s.dialect().toLowerCase().equals(lower)) {
                return s;
            }
        }
        return null;
    }
}