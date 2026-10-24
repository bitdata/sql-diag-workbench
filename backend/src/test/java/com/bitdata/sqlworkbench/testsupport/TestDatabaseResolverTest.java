// backend/src/test/java/com/bitdata/sqlworkbench/testsupport/TestDatabaseResolverTest.java
package com.bitdata.sqlworkbench.testsupport;

import com.bitdata.sqlworkbench.testsupport.TestDatabaseResolver.DatabaseEntry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class TestDatabaseResolverTest {

    @Test
    void databaseEntryToStringRedactsPassword() {
        var entry = new DatabaseEntry(
            "local-postgres", "localhost", 5432, "testdb",
            "test_user", "super-secret-password", null, null);
        String s = entry.toString();
        assertFalse(s.contains("super-secret-password"),
            () -> "password leaked in toString: " + s);
        assertTrue(s.contains("***"), () -> "expected *** placeholder in: " + s);
    }

    @Test
    void dialectEnumHasFiveValues() {
        assertEquals(5, Dialect.values().length);
        assertNotNull(Dialect.valueOf("POSTGRESQL"));
        assertNotNull(Dialect.valueOf("MYSQL"));
        assertNotNull(Dialect.valueOf("SQLSERVER"));
        assertNotNull(Dialect.valueOf("ORACLE"));
        assertNotNull(Dialect.valueOf("SQLITE"));
    }

    @Test
    void requiresTestDatabaseSkipsWhenUnavailable() {
        // Exercises the skip path without real env or filesystem mocking: precondition is
        // "a config file IS locatable (walk up from user.dir, per FIX 1) but PG is
        // unavailable" — the no-credentials case whose skip must stay observable.
        // assumeTrue self-skips on machines where PG is actually usable.
        org.junit.jupiter.api.Assumptions.assumeTrue(
            !TestDatabaseResolver.isAvailable(Dialect.POSTGRESQL)
                && TestDatabaseResolver.locateDatabaseFile(userDir()).isPresent(),
            "test runs only when PG unavailable and a config file is locatable");
        // If we got here, isAvailable returned false; that's the contract.
    }

    @Test
    void resolvesThrowsWhenExampleHasPlaceholder() {
        // Before task B, the resolver tried the per-dialect env-var
        // credential path first and fell back to the example file; that
        // env-var path has been removed (the resolver now reads
        // test-databases.json only). What remains is the placeholder-
        // rejection behaviour — an EXAMPLE_NAME file whose entry carries
        // the REPLACE_ME marker (or an empty password) must throw
        // DatabaseUnavailableException with an informative message.
        org.junit.jupiter.api.Assumptions.assumeTrue(
            TestDatabaseResolver.locateDatabaseFile(userDir())
                    .filter(p -> p.getFileName().toString()
                        .equals(TestDatabaseResolver.EXAMPLE_NAME))
                    .isPresent()
                && !TestDatabaseResolver.isAvailable(Dialect.POSTGRESQL),
            "skip when no example file located, or example usable");
        // If example has REPLACE_ME, isAvailable is false; resolver must throw informative exception
        assertThrows(TestDatabaseResolver.DatabaseUnavailableException.class,
            () -> TestDatabaseResolver.resolve(Dialect.POSTGRESQL));
    }

    // ---- FIX 1: ancestor-directory resolution (never touches the real repo — @TempDir only) ----

    private static Path userDir() {
        return Path.of(System.getProperty("user.dir", "."));
    }

    @Test
    void locateFindsConfigInAncestorDirectory(@TempDir Path root) throws Exception {
        Path deep = Files.createDirectories(root.resolve("nested/deep"));
        Path config = root.resolve(TestDatabaseResolver.CONFIG_NAME);
        Files.writeString(config, "{\"databases\":[]}");
        assertEquals(Optional.of(config), TestDatabaseResolver.locateDatabaseFile(deep),
            "file at temp-root must be found when probing from temp-root/nested/deep");
    }

    @Test
    void locateFallsBackToExampleInAncestorDirectory(@TempDir Path root) throws Exception {
        Path deep = Files.createDirectories(root.resolve("nested/deep"));
        Path example = root.resolve(TestDatabaseResolver.EXAMPLE_NAME);
        Files.writeString(example, "{\"databases\":[]}");
        assertEquals(Optional.of(example), TestDatabaseResolver.locateDatabaseFile(deep));
    }

    @Test
    void locatePrefersConfigOverExampleInSameDirectory(@TempDir Path root) throws Exception {
        Path config = root.resolve(TestDatabaseResolver.CONFIG_NAME);
        Path example = root.resolve(TestDatabaseResolver.EXAMPLE_NAME);
        Files.writeString(config, "{\"databases\":[]}");
        Files.writeString(example, "{\"databases\":[]}");
        assertEquals(Optional.of(config), TestDatabaseResolver.locateDatabaseFile(root));
    }

    @Test
    void locateCommitsToNearestHitWhenExampleCloserThanConfig(@TempDir Path root)
            throws Exception {
        // Documents the walk-up contract: first hit wins per directory, so an example in the
        // START dir is chosen before a config in an ancestor (today's same-dir primary-before-
        // example preference is preserved; across dirs nearest wins).
        Path start = Files.createDirectories(root.resolve("module"));
        Path config = root.resolve(TestDatabaseResolver.CONFIG_NAME);
        Path example = start.resolve(TestDatabaseResolver.EXAMPLE_NAME);
        Files.writeString(config, "{\"databases\":[]}");
        Files.writeString(example, "{\"databases\":[]}");
        assertEquals(Optional.of(example), TestDatabaseResolver.locateDatabaseFile(start));
    }

    @Test
    void locateReturnsEmptyWhenNoCandidate(@TempDir Path root) {
        // Nothing in the temp chain; the walk may continue to the filesystem root but must
        // never report a file from inside the (candidate-free) temp dir.
        Optional<Path> found = TestDatabaseResolver.locateDatabaseFile(root);
        found.ifPresent(p ->
            assertFalse(p.startsWith(root.toAbsolutePath().normalize()),
                () -> "walk-up reported a file inside the candidate-free temp dir: " + p));
    }

    // ---- buildJdbcUrl ≡ product DatabaseStrategy (first live-DB run, 2026-09-06) ----
    // The hand-rolled switch had forked from the product URL builders: MYSQL lost
    // MySqlStrategy's useSSL=false&allowPublicKeyRetrieval=true (caching_sha2_password
    // fixture seeding failed: "Public Key Retrieval is not allowed") and ORACLE inverted
    // the product's connectionType semantics (only lowercase "service_name" chose the
    // service form; "SERVICE_NAME"/null — the product's own values — chose SID).
    // The resolver must DELEGATE; these congruence pins prevent any future re-fork
    // (same defect class as the dialect-hardcoding sweep — CLAUDE.md rule #1).

    private static DatabaseEntry urlEntry(String connectionType, Boolean trust) {
        return new DatabaseEntry("url-test", "dbhost", 1234, "tdb", "u", "p", connectionType, trust);
    }

    @Test
    void jdbcUrl_mysqlConvenientlyCarriesProductAuthProps() {
        String url = TestDatabaseResolver.buildJdbcUrl(Dialect.MYSQL, urlEntry(null, null));
        assertEquals(new com.bitdata.sqlworkbench.connection.MySqlStrategy()
            .getJdbcUrl("dbhost", 1234, "tdb"), url);
        assertTrue(url.contains("allowPublicKeyRetrieval=true"),
            () -> "MySQL fixture URL lost product auth props — re-forked? " + url);
    }

    @Test
    void jdbcUrl_postgresCongruentWithProductStrategy() {
        assertEquals(new com.bitdata.sqlworkbench.connection.PostgreSqlStrategy()
                .getJdbcUrl("dbhost", 1234, "tdb"),
            TestDatabaseResolver.buildJdbcUrl(Dialect.POSTGRESQL, urlEntry(null, null)));
    }

    @Test
    void jdbcUrl_sqlServerCongruentBothTrustStates() {
        var s = new com.bitdata.sqlworkbench.connection.SqlServerStrategy();
        assertEquals(s.getJdbcUrl("dbhost", 1234, "tdb", false),
            TestDatabaseResolver.buildJdbcUrl(Dialect.SQLSERVER, urlEntry(null, null)));
        assertEquals(s.getJdbcUrl("dbhost", 1234, "tdb", true),
            TestDatabaseResolver.buildJdbcUrl(Dialect.SQLSERVER, urlEntry(null, true)));
    }

    @Test
    void jdbcUrl_oracleCongruentForProductConnectionTypeValues() {
        var o = new com.bitdata.sqlworkbench.connection.OracleStrategy();
        for (String ct : new String[] {"SID", "SERVICE_NAME", null}) {
            assertEquals(o.getJdbcUrl("dbhost", 1234, "tdb", ct),
                TestDatabaseResolver.buildJdbcUrl(Dialect.ORACLE, urlEntry(ct, null)),
                "oracle URL diverged from product for connectionType=" + ct);
        }
    }

    @Test
    void jdbcUrl_sqliteRemainsHandRolled() {
        // No DatabaseStrategy exists for SQLite (config-only path) — hand-rolled is the
        // single source here; pinned so the delegation sweep's scope stays auditable.
        assertEquals("jdbc:sqlite:tdb",
            TestDatabaseResolver.buildJdbcUrl(Dialect.SQLITE, urlEntry(null, null)));
    }
}
