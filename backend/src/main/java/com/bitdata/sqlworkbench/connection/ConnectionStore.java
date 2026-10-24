package com.bitdata.sqlworkbench.connection;

import com.bitdata.sqlworkbench.config.SqliteRetry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class ConnectionStore {

    private static final Logger log = LoggerFactory.getLogger(ConnectionStore.class);
    private final JdbcTemplate jdbc;

    public ConnectionStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<ConnectionConfig> findAll() {
        return jdbc.query(
            "SELECT id, name, host, port, database, username, dialect, keychain_ref, readonly, created_at, updated_at, connection_type, trust_server_certificate FROM connections ORDER BY name",
            (rs, rowNum) -> mapRow(rs)
        );
    }

    public Optional<ConnectionConfig> findById(String id) {
        List<ConnectionConfig> results = jdbc.query(
            "SELECT id, name, host, port, database, username, dialect, keychain_ref, readonly, created_at, updated_at, connection_type, trust_server_certificate FROM connections WHERE id = ?",
            (rs, rowNum) -> mapRow(rs),
            id
        );
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    public ConnectionConfig insert(String name, String host, int port, String database,
                                    String username, String dialect, String keychainRef, boolean readonly,
                                    String connectionType, boolean trustServerCertificate) {
        String id = UUID.randomUUID().toString();
        String now = Instant.now().toString();

        SqliteRetry.execute(() -> {
            jdbc.update(
                "INSERT INTO connections (id, name, host, port, database, username, dialect, keychain_ref, readonly, created_at, updated_at, connection_type, trust_server_certificate) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, name, host, port, database, username, dialect, keychainRef, readonly ? 1 : 0, now, now, connectionType, trustServerCertificate ? 1 : 0
            );
            return null;
        }, "insert connection: " + name);

        log.info("Created connection: {} ({})", name, id);
        return new ConnectionConfig(id, name, host, port, database, username, dialect, keychainRef, readonly, now, now, connectionType, trustServerCertificate);
    }

    public Optional<ConnectionConfig> update(String id, String name, String host, int port,
                                              String database, String username, String dialect, boolean readonly,
                                              String connectionType, boolean trustServerCertificate) {
        String now = Instant.now().toString();

        int updated = SqliteRetry.execute(() -> jdbc.update(
            "UPDATE connections SET name=?, host=?, port=?, database=?, username=?, dialect=?, readonly=?, updated_at=?, connection_type=?, trust_server_certificate=? WHERE id=?",
            name, host, port, database, username, dialect, readonly ? 1 : 0, now, connectionType, trustServerCertificate ? 1 : 0, id
        ), "update connection: " + id);

        if (updated == 0) {
            log.warn("Update affected 0 rows for connection id={} — row may not exist in database", id);
            return Optional.empty();
        }
        return findById(id);
    }

    public boolean delete(String id) {
        int deleted = SqliteRetry.execute(() -> jdbc.update(
            "DELETE FROM connections WHERE id = ?", id
        ), "delete connection: " + id);
        return deleted > 0;
    }

    private ConnectionConfig mapRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ConnectionConfig(
            rs.getString("id"),
            rs.getString("name"),
            rs.getString("host"),
            rs.getInt("port"),
            rs.getString("database"),
            rs.getString("username"),
            rs.getString("dialect"),
            rs.getString("keychain_ref"),
            rs.getBoolean("readonly"),
            rs.getString("created_at"),
            rs.getString("updated_at"),
            rs.getString("connection_type"),
            rs.getBoolean("trust_server_certificate")
        );
    }
}
