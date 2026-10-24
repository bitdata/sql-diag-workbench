// backend/src/test/java/com/bitdata/sqlworkbench/testsupport/LegacyEnvVarCompatNotice.java
package com.bitdata.sqlworkbench.testsupport;

import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Compatibility-period notice for legacy IT environment variables.
 *
 * <p>Before task B (chore/debt-convergence-2026-09), the project had several
 * ITs gated by {@code TEST_JDBC_<DIALECT>_{URL,USER,PASSWORD}} and
 * {@code JDBC_INTEGRATION_{URL,USER,PASSWORD,DIALECT}} env vars. After task B
 * all of those ITs are gated by {@link RequiresTestDatabase} reading
 * {@code test-databases.json} (single credential source), and
 * {@link TestDatabaseResolver} no longer reads the env vars as credential/URL
 * sources. To prevent silent footguns for users who still have the old vars
 * exported (e.g. {@code TEST_JDBC_PG_URL} in their shell profile), every
 * migrated IT class fires {@link #warnIfLegacyEnvVarsStillSet()} from its
 * {@code @BeforeAll} — emitting a single {@code WARNING} listing the present
 * vars and pointing the user at {@code test-databases.json}.
 *
 * <p>The list of legacy names is the single home for those literals: no other
 * test class reads them. The test
 * {@code LegacyEnvVarCompatNoticeTest} pins the behaviour DB-free via the
 * package-private overload that takes an explicit env-lookup function.
 */
public final class LegacyEnvVarCompatNotice {

    /** Legacy env-var names no longer read as credential/URL sources. */
    public static final List<String> LEGACY_ENV_VARS = List.of(
        "TEST_JDBC_PG_URL", "TEST_JDBC_PG_USER", "TEST_JDBC_PG_PASSWORD",
        "TEST_JDBC_MYSQL_URL", "TEST_JDBC_MYSQL_USER", "TEST_JDBC_MYSQL_PASSWORD",
        "TEST_JDBC_ORACLE_URL", "TEST_JDBC_ORACLE_USER", "TEST_JDBC_ORACLE_PASSWORD",
        "TEST_JDBC_SQLSERVER_URL", "TEST_JDBC_SQLSERVER_USER", "TEST_JDBC_SQLSERVER_PASSWORD",
        "JDBC_INTEGRATION_URL", "JDBC_INTEGRATION_USER",
        "JDBC_INTEGRATION_PASSWORD", "JDBC_INTEGRATION_DIALECT"
    );

    private static final System.Logger LOG =
        System.getLogger(LegacyEnvVarCompatNotice.class.getName());

    private LegacyEnvVarCompatNotice() {}

    /**
     * Returns the subset of {@link #LEGACY_ENV_VARS} currently present in the
     * JVM environment. Public for visibility; the canonical call site is
     * {@link #warnIfLegacyEnvVarsStillSet()} which emits the WARN.
     */
    public static List<String> findLegacyEnvVarsStillSet() {
        return findLegacyEnvVarsStillSet(System::getenv);
    }

    /** Package-private overload used by the DB-free unit test. */
    static List<String> findLegacyEnvVarsStillSet(Function<String, String> envLookup) {
        return LEGACY_ENV_VARS.stream()
            .filter(name -> envLookup.apply(name) != null)
            .collect(Collectors.toUnmodifiableList());
    }

    /**
     * Emit a single {@code WARNING} if any legacy env var is present.
     * Intentionally a no-op when none are set — the compat notice exists only
     * to nudge users who still have the old vars exported.
     */
    public static void warnIfLegacyEnvVarsStillSet() {
        List<String> present = findLegacyEnvVarsStillSet();
        if (present.isEmpty()) return;
        LOG.log(System.Logger.Level.WARNING,
            "Legacy IT env var(s) still set: " + present + ". "
            + "These are no longer used as credential or URL sources by the "
            + "integration tests — test-databases.json (repo root) is the "
            + "single credential source. Unset them to silence this notice "
            + "and to avoid confusion about which path your tests are taking.");
    }
}
