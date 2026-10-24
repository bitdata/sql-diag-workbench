package com.bitdata.sqlworkbench.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Configuration
public class SqliteConfig {

    private static final Logger log = LoggerFactory.getLogger(SqliteConfig.class);

    // spec §4.5: default data dir on Windows = %APPDATA%/sql-diag-workbench/data (i.e. the
    // pre-feature behavior). SpEL's `systemEnvironment` reads OS env vars (correct); the older
    // `systemProperties` reads JVM system properties, where APPDATA is null on a stock Windows JVM.
    @org.springframework.beans.factory.annotation.Value("${sql-workbench.data-dir:#{systemEnvironment['APPDATA'] != null ? T(java.nio.file.Paths).get(systemEnvironment['APPDATA'], 'sql-diag-workbench', 'data').toString() : T(java.nio.file.Paths).get(T(java.lang.System).getProperty('user.home'), '.sql-diag-workbench', 'data').toString()}}")
    private String dataDir;

    @Bean
    public DataSource sqliteDataSource() {
        Path dataDirPath = Paths.get(dataDir);
        try {
            Files.createDirectories(dataDirPath);
        } catch (Exception e) {
            log.error("Failed to create data directory: {}", dataDirPath, e);
        }

        String dbPath = dataDirPath.resolve("workbench.db").toAbsolutePath().toString();
        log.info("SQLite database path: {}", dbPath);

        DriverManagerDataSource ds = new com.bitdata.sqlworkbench.storage.StorageDataSource();
        ds.setDriverClassName("org.sqlite.JDBC");
        ds.setUrl("jdbc:sqlite:" + dbPath);
        return ds;
    }

    @Bean
    public JdbcTemplate jdbcTemplate(DataSource sqliteDataSource) {
        JdbcTemplate jdbc = new JdbcTemplate(sqliteDataSource);
        // WAL mode enables concurrent reads during writes and reduces busy errors
        jdbc.execute("PRAGMA journal_mode=WAL");
        jdbc.execute("PRAGMA busy_timeout=5000");
        initSchema(jdbc);
        return jdbc;
    }

    private void initSchema(JdbcTemplate jdbc) {
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS connections (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                host TEXT NOT NULL,
                port INTEGER NOT NULL,
                database TEXT NOT NULL,
                username TEXT NOT NULL,
                dialect TEXT NOT NULL DEFAULT 'postgresql',
                keychain_ref TEXT NOT NULL,
                readonly INTEGER NOT NULL DEFAULT 1,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL,
                connection_type TEXT,
                trust_server_certificate INTEGER NOT NULL DEFAULT 0
            )
            """);

        // Idempotent ALTER for existing databases that pre-date connection_type.
        // PRAGMA table_info returns one row per column; skip ALTER if present.
        java.util.List<java.util.Map<String, Object>> cols = jdbc.queryForList(
            "PRAGMA table_info(connections)"
        );
        boolean hasConnectionType = cols.stream()
            .anyMatch(c -> "connection_type".equals(c.get("name")));
        if (!hasConnectionType) {
            jdbc.execute("ALTER TABLE connections ADD COLUMN connection_type TEXT");
            log.info("Schema migration: added connections.connection_type column");
        }

        boolean hasTrustServerCertificate = cols.stream()
            .anyMatch(c -> "trust_server_certificate".equals(c.get("name")));
        if (!hasTrustServerCertificate) {
            jdbc.execute("ALTER TABLE connections ADD COLUMN trust_server_certificate INTEGER NOT NULL DEFAULT 0");
            log.info("Schema migration: added connections.trust_server_certificate column");
        }

        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_connections_name ON connections(name)");

        // Create sessions and history tables from the plan schema
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS sessions (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                sql_text TEXT NOT NULL,
                dialect TEXT NOT NULL DEFAULT 'postgresql',
                connection_id TEXT,
                fragment_tree_json TEXT,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL
            )
            """);

        if (jdbc.queryForList("PRAGMA table_info(sessions)").stream()
                .noneMatch(c -> "workspace_snapshot_json".equals(c.get("name")))) {
            jdbc.execute("ALTER TABLE sessions ADD COLUMN workspace_snapshot_json TEXT");
        }
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS session_edit_links (
                sequence INTEGER PRIMARY KEY AUTOINCREMENT,
                link_id TEXT NOT NULL UNIQUE,
                source_session_id TEXT NOT NULL,
                source_node_id TEXT NOT NULL,
                source_tab_name TEXT NOT NULL,
                target_session_id TEXT NOT NULL UNIQUE,
                target_node_id TEXT NOT NULL,
                target_tab_name TEXT NOT NULL,
                created_at TEXT NOT NULL
            )
            """);
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_edit_links_source ON session_edit_links(source_session_id)");

        // Receipts outlive deleted sessions: an old request must never recreate one.
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS session_creation_receipts (
                request_id TEXT PRIMARY KEY,
                request_fingerprint TEXT NOT NULL,
                session_id TEXT NOT NULL UNIQUE,
                created_at TEXT NOT NULL
            )
            """);

        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS history (
                id TEXT PRIMARY KEY,
                session_id TEXT NOT NULL,
                sql_text TEXT NOT NULL,
                executed_at TEXT NOT NULL,
                duration_ms INTEGER,
                row_count INTEGER
            )
            """);

        if (jdbc.queryForList("PRAGMA table_info(history)").stream()
                .noneMatch(c -> "result_completeness_json".equals(c.get("name")))) {
            jdbc.execute("ALTER TABLE history ADD COLUMN result_completeness_json TEXT");
        }

        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS diagnostic_reports (
                id TEXT PRIMARY KEY,
                session_id TEXT NOT NULL,
                fragment_id TEXT NOT NULL,
                findings_json TEXT NOT NULL,
                created_at TEXT NOT NULL
            )
            """);

        if (jdbc.queryForList("PRAGMA table_info(diagnostic_reports)").stream()
                .noneMatch(c -> "evaluation_json".equals(c.get("name")))) {
            jdbc.execute("ALTER TABLE diagnostic_reports ADD COLUMN evaluation_json TEXT");
        }

        // Spec §8.3 — audit log of editor operations that ADR-0002 classified
        // as "navigation" (i.e. the user's edit collapsed to an existing
        // fragment). Without this log, the user's keystrokes would be silently
        // dropped, which loses the trail of "what did the user actually
        // try". `action` is "NAVIGATE" for equivalence-matched edits and
        // "EDIT" for new fragments. `equivalent_node_id` is non-null only
        // for NAVIGATE rows.
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS edit_audit (
                id TEXT PRIMARY KEY,
                session_id TEXT NOT NULL,
                before_sql TEXT NOT NULL,
                after_sql TEXT NOT NULL,
                action TEXT NOT NULL,
                equivalent_node_id TEXT,
                classified_at TEXT NOT NULL
            )
            """);

        // Secondary indexes for query patterns (from plan's Eng Review)
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_history_session ON history(session_id)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_history_executed ON history(executed_at DESC)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_sessions_updated ON sessions(updated_at)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_diagnostic_reports_session ON diagnostic_reports(session_id)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_edit_audit_session ON edit_audit(session_id)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_edit_audit_classified ON edit_audit(classified_at DESC)");

        jdbc.execute("CREATE TABLE IF NOT EXISTS storage_policy (id INTEGER PRIMARY KEY CHECK(id=1), enabled INTEGER NOT NULL, retention_days INTEGER NOT NULL, max_storage_mib INTEGER NOT NULL, revision INTEGER NOT NULL)");
        jdbc.execute("INSERT OR IGNORE INTO storage_policy VALUES(1,0,30,500,1)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS storage_protection (profile_id TEXT NOT NULL, session_id TEXT NOT NULL, PRIMARY KEY(profile_id,session_id))");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_storage_protection_session ON storage_protection(session_id)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS storage_reclaimable (session_id TEXT PRIMARY KEY)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS storage_generation (id INTEGER PRIMARY KEY CHECK(id=1), revision INTEGER NOT NULL)");
        jdbc.execute("INSERT OR IGNORE INTO storage_generation VALUES(1,1)");
        // No receipt cascade: deleted targets must still reject replay after cleanup/restart.
        jdbc.execute("""
            CREATE TRIGGER IF NOT EXISTS session_storage_delete AFTER DELETE ON sessions BEGIN
              DELETE FROM diagnostic_reports WHERE session_id=OLD.id;
              DELETE FROM edit_audit WHERE session_id=OLD.id;
              DELETE FROM storage_protection WHERE session_id=OLD.id;
              DELETE FROM storage_reclaimable WHERE session_id=OLD.id;
              DELETE FROM session_edit_links WHERE NOT EXISTS(SELECT 1 FROM sessions WHERE id=source_session_id)
                AND NOT EXISTS(SELECT 1 FROM sessions WHERE id=target_session_id);
              UPDATE storage_generation SET revision=revision+1 WHERE id=1;
            END
            """);
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_reports_created ON diagnostic_reports(created_at,id)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_retention_history ON history(julianday(executed_at),id)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_retention_reports ON diagnostic_reports(julianday(created_at),id)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_retention_audit ON edit_audit(julianday(classified_at),id)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_retention_sessions ON sessions(julianday(updated_at),id)");

        jdbc.execute("CREATE TABLE IF NOT EXISTS connection_credentials (connection_id TEXT PRIMARY KEY, intent TEXT NOT NULL, format TEXT, ciphertext BLOB, error_reason TEXT)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS credential_migration (id INTEGER PRIMARY KEY CHECK(id=1), status TEXT NOT NULL, unresolved INTEGER NOT NULL, error_reason TEXT, source_hash TEXT)");
        jdbc.execute("INSERT OR IGNORE INTO credential_migration(id,status,unresolved) VALUES(1,'not_started',0)");
        jdbc.execute("CREATE TRIGGER IF NOT EXISTS connection_credential_delete AFTER DELETE ON connections BEGIN DELETE FROM connection_credentials WHERE connection_id=OLD.id; END");
        log.info("SQLite schema initialized (WAL mode)");
    }
}
