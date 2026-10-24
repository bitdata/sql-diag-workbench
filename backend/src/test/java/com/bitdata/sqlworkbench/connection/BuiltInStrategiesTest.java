package com.bitdata.sqlworkbench.connection;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DB-free unit tests for the dialect-registry single source of truth.
 *
 * <p>The Spring-context consistency test
 * ({@link BuiltInStrategiesConsistencyTest}) is the load-bearing pin — it
 * bootstraps the full application context. These unit tests are
 * belt-and-suspenders: they assert {@link BuiltInStrategies#ALL} holds
 * exactly the four built-in dialects and that {@code findByDialect} does
 * the case-insensitive lookup other code relies on.
 */
class BuiltInStrategiesTest {

    @Test
    void all_containsExactlyFourBuiltInDialects() {
        Set<String> dialects = new HashSet<>();
        for (DatabaseStrategy s : BuiltInStrategies.ALL) {
            dialects.add(s.dialect().toLowerCase());
        }
        assertEquals(Set.of("postgresql", "mysql", "oracle", "sqlserver"), dialects,
            "BuiltInStrategies.ALL must contain the four built-in dialects");
    }

    @Test
    void findByDialect_returnsStrategyForKnownDialect_caseInsensitive() {
        assertNotNull(BuiltInStrategies.findByDialect("postgresql"));
        assertNotNull(BuiltInStrategies.findByDialect("PostgreSQL"));
        assertNotNull(BuiltInStrategies.findByDialect("MYSQL"));
        assertNotNull(BuiltInStrategies.findByDialect("oracle"));
        assertNotNull(BuiltInStrategies.findByDialect("sqlserver"));
    }

    @Test
    void findByDialect_returnsNullForUnknownOrNullDialect() {
        assertNull(BuiltInStrategies.findByDialect(null));
        assertNull(BuiltInStrategies.findByDialect(""));
        assertNull(BuiltInStrategies.findByDialect("mariadb"));
    }

    @Test
    void all_entries_haveNonNullDialectProfile() {
        for (DatabaseStrategy s : BuiltInStrategies.ALL) {
            assertNotNull(s.getDialectProfile(),
                s.getClass().getSimpleName() + " must expose a DialectProfile");
            assertEquals(s.dialect().toLowerCase(),
                s.getDialectProfile().name().toLowerCase(),
                s.getClass().getSimpleName() + " profile name must match dialect()");
        }
    }
}