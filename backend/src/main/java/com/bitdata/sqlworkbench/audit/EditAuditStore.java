package com.bitdata.sqlworkbench.audit;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Spec §8.3 — store for editor-operation audit entries.
 *
 * <p>Backing table: {@code edit_audit}. One row per classification decision
 * (NAVIGATE or EDIT), capturing the user's actual keystrokes (before/after
 * SQL) so the audit trail survives even when the edit was "swallowed" by
 * ADR-0002's equivalence collapse.
 *
 * <p>The store intentionally does not provide a query API in this commit —
 * the UI is deferred. SQL-level access via {@code sqlite3 workbench.db
 * "SELECT * FROM edit_audit"} is the immediate read path.
 */
@Component
public class EditAuditStore {

    public enum Action { NAVIGATE, EDIT }

    public record AuditEntry(
        String id,
        String sessionId,
        String beforeSql,
        String afterSql,
        Action action,
        String equivalentNodeId,
        String classifiedAt
    ) {}

    private final JdbcTemplate jdbc;

    public EditAuditStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public AuditEntry record(String sessionId, String beforeSql, String afterSql,
                             Action action, String equivalentNodeId) {
        String id = UUID.randomUUID().toString();
        String classifiedAt = Instant.now().toString();
        jdbc.update("""
            INSERT INTO edit_audit
              (id, session_id, before_sql, after_sql, action, equivalent_node_id, classified_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """,
            id, sessionId, beforeSql, afterSql, action.name(),
            equivalentNodeId, classifiedAt);
        return new AuditEntry(id, sessionId, beforeSql, afterSql, action,
            equivalentNodeId, classifiedAt);
    }

    public List<Map<String, Object>> recentForSession(String sessionId, int limit) {
        return jdbc.queryForList("""
            SELECT id, session_id, before_sql, after_sql, action,
                   equivalent_node_id, classified_at
            FROM edit_audit
            WHERE session_id = ?
            ORDER BY classified_at DESC
            LIMIT ?
            """, sessionId, limit);
    }

    /** SHA-1 short-id, used for stable IDs in tests if needed. */
    public static String hashId(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] hash = md.digest(input.getBytes());
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-1 not available", e);
        }
    }
}
