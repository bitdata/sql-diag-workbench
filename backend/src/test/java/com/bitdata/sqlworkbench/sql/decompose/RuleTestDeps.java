package com.bitdata.sqlworkbench.sql.decompose;

import com.bitdata.sqlworkbench.connection.BuiltInStrategies;
import com.bitdata.sqlworkbench.connection.DatabaseStrategy;
import com.bitdata.sqlworkbench.connection.DatabaseStrategyFactory;
import com.bitdata.sqlworkbench.sql.SqlDialectResolver;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Test-only {@link SqlDialectResolver} shared by every rule test.
 *
 * <p>Built from {@link BuiltInStrategies#ALL} (the single source of truth)
 * but filtered to the dialects rule tests actually exercise — PostgreSQL +
 * MySQL. The minimal subset is intentional: rule tests don't need the
 * Oracle/SqlServer strategies, and pulling all four would make these unit
 * tests slower and slightly more coupled to the production wiring.
 *
 * <p>The consistency between this minimal subset and
 * {@link BuiltInStrategies#ALL} is enforced by
 * {@link com.bitdata.sqlworkbench.connection.BuiltInStrategiesConsistencyTest},
 * which bootstraps the full Spring context and asserts the bean-derived
 * dialect set equals {@code ALL}. If a 5th strategy is added to {@code ALL}
 * without including it here, that test still passes (the production
 * wiring is what matters for the single-source guarantee); if a strategy is
 * removed from {@code ALL}, the consistency test fires.
 */
public final class RuleTestDeps {
    private RuleTestDeps() {}

    /** Minimal set: only the dialects rule tests use. */
    private static final Set<String> TEST_DIALECTS = Set.of("postgresql", "mysql");

    public static SqlDialectResolver testResolver() {
        Map<String, DatabaseStrategy> beans = new LinkedHashMap<>();
        for (DatabaseStrategy s : BuiltInStrategies.ALL) {
            if (TEST_DIALECTS.contains(s.dialect().toLowerCase())) {
                beans.put(s.getClass().getSimpleName(), s);
            }
        }
        return new SqlDialectResolver(new DatabaseStrategyFactory(beans));
    }
}