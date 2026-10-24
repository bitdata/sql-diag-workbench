// backend/src/test/java/com/bitdata/sqlworkbench/testsupport/TestDatabaseResolver.java
package com.bitdata.sqlworkbench.testsupport;

import com.bitdata.sqlworkbench.connection.MySqlStrategy;
import com.bitdata.sqlworkbench.connection.OracleStrategy;
import com.bitdata.sqlworkbench.connection.PostgreSqlStrategy;
import com.bitdata.sqlworkbench.connection.SqlServerStrategy;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

public final class TestDatabaseResolver {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PLACEHOLDER = "REPLACE_ME";
    static final String CONFIG_NAME = "test-databases.json";
    static final String EXAMPLE_NAME = "test-databases.example.json";

    private TestDatabaseResolver() {}

    public record DatabaseEntry(
        String name, String host, int port, String database,
        String username, String password,
        String connectionType, Boolean trustServerCertificate
    ) {
        @Override
        public String toString() {
            return "DatabaseEntry[name=" + name + ",host=" + host + ",port=" + port
                + ",database=" + database + ",username=" + username
                + ",password=***,connectionType=" + connectionType
                + ",trustServerCertificate=" + trustServerCertificate + "]";
        }
    }

    /** Thrown when no usable entry found for the requested dialect. */
    public static class DatabaseUnavailableException extends RuntimeException {
        public DatabaseUnavailableException(String msg) { super(msg); }
    }

    public static boolean isAvailable(Dialect dialect) {
        try { resolve(dialect); return true; }
        catch (DatabaseUnavailableException e) { return false; }
    }

    /**
     * Why {@code dialect} is unavailable — the resolver's detailed exception message verbatim
     * (missing-file / placeholder / no-entry / parse-error), or empty when usable.
     * CLAUDE.md rule #2: RequiresTestDatabaseCondition must surface THIS string in the JUnit
     * skip report instead of a generic one; the returned Optional is never empty-text (a
     * message-less unexpected exception degrades to a generic non-null reason).
     */
    public static Optional<String> unavailableReason(Dialect dialect) {
        try {
            resolve(dialect);
            return Optional.empty();
        } catch (DatabaseUnavailableException e) {
            String msg = e.getMessage();
            return Optional.of(msg == null || msg.isBlank()
                ? dialect.name().toLowerCase() + " unavailable (no detail)" : msg);
        } catch (RuntimeException e) {
            return Optional.of("resolver failed unexpectedly: " + e.getClass().getSimpleName()
                + (e.getMessage() == null ? "" : ": " + e.getMessage()));
        }
    }

    /**
     * Single source of truth: test-databases.json (see {@value #CONFIG_NAME} /
     * {@value #EXAMPLE_NAME}). The legacy per-dialect environment-variable
     * credential path that previously took priority over JSON has been
     * removed (task B, chore/debt-convergence-2026-09) to drop the silent
     * dual-track that disguised "gate bypassed" as "credentials missing" on
     * no-credential machines (acceptance-gate §1 I1 category). Any caller
     * that still has the legacy env vars set is informed via
     * {@code LegacyEnvVarCompatNotice.warnIfLegacyEnvVarsStillSet()}.
     */
    public static DatabaseEntry resolve(Dialect dialect) {
        return resolveFromJson(dialect);
    }

    /**
     * Delegates to the product's DatabaseStrategy — the single source of truth for
     * connection URLs (same rule as the dialect-hardcoding sweep). The former
     * hand-rolled switch had silently forked: MySQL dropped the caching_sha2 auth
     * props and Oracle inverted the connectionType semantics (caught by the first
     * live-DB run 2026-09-06; congruence pinned in TestDatabaseResolverTest).
     */
    public static String buildJdbcUrl(Dialect dialect, DatabaseEntry e) {
        return switch (dialect) {
            case POSTGRESQL -> new PostgreSqlStrategy().getJdbcUrl(e.host(), e.port(), e.database());
            case MYSQL      -> new MySqlStrategy().getJdbcUrl(e.host(), e.port(), e.database());
            case SQLSERVER  -> new SqlServerStrategy()
                .getJdbcUrl(e.host(), e.port(), e.database(), Boolean.TRUE.equals(e.trustServerCertificate()));
            case ORACLE     -> new OracleStrategy()
                .getJdbcUrl(e.host(), e.port(), e.database(), e.connectionType());
            // No product strategy exists for SQLite (config-only path) — hand-rolled
            // is the single source here; pinned so the delegation sweep stays auditable.
            case SQLITE     -> "jdbc:sqlite:" + e.database();
        };
    }

    /**
     * Locate the config file by walking UP from {@code startDir} to the filesystem root.
     * Per directory, {@value #CONFIG_NAME} wins over {@value #EXAMPLE_NAME}; within the walk
     * the FIRST existing file wins (a closer directory commits, matching the old
     * same-directory primary-then-example preference). Upward walk is required because
     * Surefire/failsafe fork the test JVM with CWD = module basedir ({@code backend/}), while
     * the spec (§4.1/§10) puts both candidate files at the repo root — a bare CWD-relative
     * lookup could never find them from {@code cd backend && mvn verify}. The repo-root
     * layout (only the root carries the files) makes the walk deterministic; crossing a .git
     * boundary is deliberately NOT special-cased.
     *
     * <p>Package-visible for unit testing (@TempDir ancestor chains); production callers
     * use {@link #resolveFromJson} via {@link #resolve}.
     */
    static Optional<Path> locateDatabaseFile(Path startDir) {
        Path dir = startDir.toAbsolutePath().normalize();
        while (dir != null) {
            Path primary = dir.resolve(CONFIG_NAME);
            if (Files.exists(primary)) return Optional.of(primary);
            Path fallback = dir.resolve(EXAMPLE_NAME);
            if (Files.exists(fallback)) return Optional.of(fallback);
            dir = dir.getParent();
        }
        return Optional.empty();
    }

    private static DatabaseEntry resolveFromJson(Dialect dialect) {
        Path startDir = Path.of(System.getProperty("user.dir", "."));
        Path file = locateDatabaseFile(startDir).orElseThrow(() -> new DatabaseUnavailableException(
            CONFIG_NAME + " not found at or above " + startDir.toAbsolutePath().normalize()
            + " (walked up looking for " + CONFIG_NAME + ", then " + EXAMPLE_NAME
            + ", in every ancestor directory)"));
        try {
            JsonNode root = MAPPER.readTree(Files.readAllBytes(file));
            JsonNode databases = root.get("databases");
            if (databases == null || !databases.isArray()) {
                throw new DatabaseUnavailableException(
                    file + " has no 'databases' array");
            }
            String dialectKey = dialect.name().toLowerCase();
            for (JsonNode db : databases) {
                if (!dialectKey.equals(db.path("dialect").asText())) continue;
                String pwd = db.path("password").asText("");
                if (PLACEHOLDER.equals(pwd) || pwd.isBlank()) {
                    throw new DatabaseUnavailableException(
                        "test-databases.json " + dialectKey + " entry has placeholder/empty password");
                }
                return new DatabaseEntry(
                    db.path("name").asText(""),
                    db.path("host").asText(""),
                    db.path("port").asInt(0),
                    db.path("database").asText(""),
                    db.path("username").asText(""),
                    pwd,
                    db.hasNonNull("connectionType") ? db.get("connectionType").asText() : null,
                    db.hasNonNull("trustServerCertificate") ? db.get("trustServerCertificate").asBoolean() : null
                );
            }
            throw new DatabaseUnavailableException(
                file + " has no entry for dialect " + dialectKey);
        } catch (DatabaseUnavailableException e) {
            throw e;
        } catch (Exception e) {
            throw new DatabaseUnavailableException("failed to parse " + file + ": " + e.getMessage());
        }
    }
}
