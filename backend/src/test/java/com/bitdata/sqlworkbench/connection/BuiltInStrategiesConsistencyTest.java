package com.bitdata.sqlworkbench.connection;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Consistency pin: every {@link DatabaseStrategy} registered as a Spring
 * bean must also appear in {@link BuiltInStrategies#ALL}.
 *
 * <p>Before this pin, the dialect registry was assembled in three places
 * (Spring auto-collection via the {@code Map<String, DatabaseStrategy>}
 * constructor of {@link DatabaseStrategyFactory}, the static fallback
 * holder {@code DialectParserConfigs}, and the test-only
 * {@code RuleTestDeps}). Adding a 5th dialect required synchronising all
 * three sites; missing one caused the canonicalize chain to silently fall
 * back to PostgreSQL. This test fires if the two ends drift — a bean
 * added without updating {@code ALL}, or vice versa.
 *
 * <p>The complementary direction (a 5th strategy added to {@code ALL}
 * without a matching Spring bean) is pinned explicitly by
 * {@link #allEntries_haveMatchingSpringBean()}. The set-equality assertion
 * above already fires on drift in either direction, but its message cannot
 * say which end drifted; the dedicated test fails with the offending
 * dialect named. If this direction ever regressed unnoticed, the Spring
 * context and {@code DialectParserConfigs} would disagree on the dialect
 * set and production callers would hit the silent PostgreSQL fallback
 * (WARN-logged — see {@link com.bitdata.sqlworkbench.sql.SqlDialectResolver#getProfile}).
 */
@SpringBootTest
class BuiltInStrategiesConsistencyTest {

    @Autowired
    @SuppressWarnings("unused")
    private DatabaseStrategyFactory factory;

    @Test
    void beanDialects_areContainedIn_builtInStrategiesAll() {
        Set<String> beanDialects = new HashSet<>();
        for (DatabaseStrategy bean : factory.dialects()) {
            beanDialects.add(bean.dialect().toLowerCase());
        }

        Set<String> builtInDialects = new HashSet<>();
        for (DatabaseStrategy s : BuiltInStrategies.ALL) {
            builtInDialects.add(s.dialect().toLowerCase());
        }

        // Every bean-derived dialect must also live in the single source.
        assertEquals(builtInDialects, beanDialects,
            "Bean-derived dialects drifted from BuiltInStrategies.ALL — adding a 5th " +
                "dialect requires updating BuiltInStrategies.ALL");
    }

    @Test
    void allEntries_haveMatchingSpringBean() {
        // Final review M3: explicit ALL→bean direction with the offending
        // dialect named. Adding a strategy to BuiltInStrategies.ALL without
        // also registering it as a @Component splits the Spring context's
        // registry from DialectParserConfigs' withBuiltinStrategies() view.
        Set<String> beanDialects = new HashSet<>();
        for (DatabaseStrategy bean : factory.dialects()) {
            beanDialects.add(bean.dialect().toLowerCase());
        }

        for (DatabaseStrategy s : BuiltInStrategies.ALL) {
            String dialect = s.dialect().toLowerCase();
            assertTrue(beanDialects.contains(dialect),
                "BuiltInStrategies.ALL lists '" + s.dialect() + "' but the Spring " +
                    "container has no matching DatabaseStrategy bean — register the " +
                    "strategy as @Component (or remove it from ALL)");
        }
    }
}