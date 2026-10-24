package com.bitdata.sqlworkbench.connection;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class DatabaseStrategyFactory {

    private final Map<String, DatabaseStrategy> strategies = new ConcurrentHashMap<>();

    public DatabaseStrategyFactory(Map<String, DatabaseStrategy> strategyMap) {
        strategyMap.forEach((beanName, strategy) -> {
            String dialect = strategy.dialect();
            strategies.put(dialect.toLowerCase(), strategy);
            strategies.put(dialect.toUpperCase(), strategy);
        });
    }

    /**
     * Builds a factory from the canonical {@link BuiltInStrategies#ALL} list.
     *
     * <p>Used by the static fallback holder
     * {@link com.bitdata.sqlworkbench.sql.DialectParserConfigs} and by test
     * scaffolding ({@code RuleTestDeps}) so they derive from the same
     * single source as the Spring auto-collection — closing the "add a 5th
     * dialect requires syncing 3 sites" hole that previously caused the
     * canonicalize chain to silently fall back to PostgreSQL.
     */
    public static DatabaseStrategyFactory withBuiltinStrategies() {
        Map<String, DatabaseStrategy> beans = new java.util.LinkedHashMap<>();
        for (DatabaseStrategy s : BuiltInStrategies.ALL) {
            beans.put(s.getClass().getSimpleName(), s);
        }
        return new DatabaseStrategyFactory(beans);
    }

    public DatabaseStrategy get(String dialect) {
        String normalizedDialect = (dialect != null ? dialect : "postgresql").toLowerCase();
        DatabaseStrategy strategy = strategies.get(normalizedDialect);
        if (strategy == null) {
            throw new IllegalArgumentException("Unsupported database dialect: " + dialect);
        }
        return strategy;
    }

    /**
     * Returns the distinct {@link DatabaseStrategy} instances held by this
     * factory. The factory stores each strategy under both lower- and
     * upper-case keys, so callers that care about uniqueness should
     * de-duplicate (the bean-set comparison in
     * {@link BuiltInStrategiesConsistencyTest} does this via a
     * {@code Set<String>} of dialects).
     *
     * <p>Used by the consistency pin to assert the bean-derived dialect
     * set matches {@link BuiltInStrategies#ALL}.
     */
    public java.util.Collection<DatabaseStrategy> dialects() {
        return java.util.Collections.unmodifiableCollection(strategies.values());
    }
}
