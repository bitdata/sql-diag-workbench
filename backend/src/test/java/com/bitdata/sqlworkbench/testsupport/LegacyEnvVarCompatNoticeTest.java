// backend/src/test/java/com/bitdata/sqlworkbench/testsupport/LegacyEnvVarCompatNoticeTest.java
package com.bitdata.sqlworkbench.testsupport;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DB-free pin for the compatibility-period notice. The legacy env-var names
 * are listed in {@link LegacyEnvVarCompatNotice#LEGACY_ENV_VARS}; the helper's
 * behaviour must stay testable without mutating the real JVM environment, so
 * the package-private overload accepts an explicit env-lookup function.
 */
class LegacyEnvVarCompatNoticeTest {

    private static Function<String, String> lookup(Map<String, String> map) {
        return map::get;
    }

    @Test
    void findsAllLegacyEnvVarsWhenSet() {
        // Build a map where every legacy name has a value; assert each one is
        // reported (proves the LEGACY_ENV_VARS list still matches the literal
        // names callers expect to migrate from — a stray rename here would
        // silently break the WARN for users still exporting the old vars).
        Map<String, String> env = new java.util.HashMap<>();
        for (String name : LegacyEnvVarCompatNotice.LEGACY_ENV_VARS) {
            env.put(name, "x");
        }
        List<String> present =
            LegacyEnvVarCompatNotice.findLegacyEnvVarsStillSet(lookup(env));
        assertEquals(LegacyEnvVarCompatNotice.LEGACY_ENV_VARS.size(), present.size());
        assertTrue(present.containsAll(LegacyEnvVarCompatNotice.LEGACY_ENV_VARS));
    }

    @Test
    void reportsEmptyWhenNoLegacyEnvVarIsSet() {
        List<String> present = LegacyEnvVarCompatNotice
            .findLegacyEnvVarsStillSet(lookup(Map.of()));
        assertEquals(List.of(), present,
            "no legacy env vars set ⇒ WARN must be a no-op");
    }

    @Test
    void reportsOnlyThoseThatAreActuallySet() {
        // Mixed: only TEST_JDBC_PG_URL + JDBC_INTEGRATION_DIALECT set — the
        // helper must surface exactly those two, not the whole family.
        List<String> present = LegacyEnvVarCompatNotice.findLegacyEnvVarsStillSet(lookup(
            Map.of("TEST_JDBC_PG_URL", "jdbc:postgresql://h/p",
                   "JDBC_INTEGRATION_DIALECT", "postgresql",
                   "PATH", "/usr/bin")));
        assertEquals(List.of("TEST_JDBC_PG_URL", "JDBC_INTEGRATION_DIALECT"), present,
            "unrelated env vars (PATH) must not appear in the WARN body");
    }

    @Test
    void legacyEnvVarListCoversBothFamilies() {
        // Regression pin: if a future iteration adds a new legacy name (e.g.
        // for a new dialect), it must be appended to LEGACY_ENV_VARS so the
        // WARN still catches users exporting it.
        assertTrue(LegacyEnvVarCompatNotice.LEGACY_ENV_VARS.stream()
                .anyMatch(n -> n.startsWith("TEST_JDBC_PG_")),
            "PG family missing from LEGACY_ENV_VARS");
        assertTrue(LegacyEnvVarCompatNotice.LEGACY_ENV_VARS.stream()
                .anyMatch(n -> n.startsWith("TEST_JDBC_MYSQL_")),
            "MySQL family missing from LEGACY_ENV_VARS");
        assertTrue(LegacyEnvVarCompatNotice.LEGACY_ENV_VARS.stream()
                .anyMatch(n -> n.startsWith("TEST_JDBC_ORACLE_")),
            "Oracle family missing from LEGACY_ENV_VARS");
        assertTrue(LegacyEnvVarCompatNotice.LEGACY_ENV_VARS.stream()
                .anyMatch(n -> n.startsWith("TEST_JDBC_SQLSERVER_")),
            "SqlServer family missing from LEGACY_ENV_VARS");
        assertTrue(LegacyEnvVarCompatNotice.LEGACY_ENV_VARS.stream()
                .anyMatch(n -> n.startsWith("JDBC_INTEGRATION_")),
            "JDBC_INTEGRATION family missing from LEGACY_ENV_VARS");
    }
}
