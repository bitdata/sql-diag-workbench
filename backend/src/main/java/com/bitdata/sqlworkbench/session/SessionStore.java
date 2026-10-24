package com.bitdata.sqlworkbench.session;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
import java.util.*;

@Repository
public class SessionStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private static final String COLUMNS = "id,name,sql_text,dialect,connection_id,fragment_tree_json,workspace_snapshot_json,created_at,updated_at";
    private static final RowMapper<Session> MAPPER = (rs, n) -> new Session(
        rs.getString("id"), rs.getString("name"), rs.getString("sql_text"),
        rs.getString("dialect"), rs.getString("connection_id"), rs.getString("fragment_tree_json"),
        rs.getString("workspace_snapshot_json"), Instant.parse(rs.getString("created_at")),
        Instant.parse(rs.getString("updated_at")));

    public SessionStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(new DataSourceTransactionManager(Objects.requireNonNull(jdbc.getDataSource())));
    }
    public List<Session> findAll() {
        return jdbc.query("SELECT " + COLUMNS + " FROM sessions ORDER BY updated_at DESC", MAPPER);
    }
    public Optional<Session> findById(String id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM sessions WHERE id=?", MAPPER, id).stream().findFirst();
    }
    /** Creation only: stale writers must never resurrect a deleted session. */
    public Session save(Session s) {
        Instant now = Instant.now();
        jdbc.update("INSERT INTO sessions (" + COLUMNS + ") VALUES (?,?,?,?,?,?,?,?,?)",
            s.id(), s.name(), s.sqlText(), s.dialect(), s.connectionId(), s.fragmentTreeJson(),
            s.workspaceSnapshotJson(), now.toString(), now.toString());
        return findById(s.id()).orElseThrow();
    }
    public Session createWithOrigin(Session s, SessionEditLink.Origin origin) {
        return createWithOrigin(s, origin, null, null);
    }
    public static class CreationFailure extends RuntimeException {
        public final String code;
        public CreationFailure(String code, String message) { super(message); this.code = code; }
    }
    /** Reserve receipt -> create session + origin -> commit. All three roll back together. */
    public Session createWithOrigin(Session s, SessionEditLink.Origin origin, String requestId, String fingerprint) {
        return createWithOrigin(s, origin, requestId, fingerprint, null);
    }
    public Session createWithOrigin(Session s, SessionEditLink.Origin origin, String requestId, String fingerprint, String profileId) {
        return tx.execute(status -> {
            jdbc.update("UPDATE storage_generation SET revision=revision+1 WHERE id=1");
            if (requestId != null) {
                // The first operation in the transaction is a write, avoiding SQLite
                // read-to-write lock upgrades when concurrent callers replay the key.
                int reserved = jdbc.update("""
                    INSERT INTO session_creation_receipts (request_id,request_fingerprint,session_id,created_at)
                    VALUES (?,?,?,?) ON CONFLICT(request_id) DO NOTHING
                    """, requestId, fingerprint, s.id(), Instant.now().toString());
                if (reserved == 0) {
                    var receipt = jdbc.queryForMap("SELECT request_fingerprint,session_id FROM session_creation_receipts WHERE request_id=?", requestId);
                    if (!fingerprint.equals(receipt.get("request_fingerprint")))
                        throw new CreationFailure("CREATION_REQUEST_CONFLICT", "This creation request was already used with different content. Start a new operation.");
                    Session replay = findById((String) receipt.get("session_id")).orElseThrow(() ->
                        new CreationFailure("CREATION_TARGET_GONE", "The tab created by this operation has been deleted. Start a new operation."));
                    protect(replay.id(), profileId);
                    return replay;
                }
            }
            Session created = save(s);
            protect(created.id(), profileId);
            if (origin != null) {
                jdbc.update("""
                    INSERT INTO session_edit_links
                    (link_id,source_session_id,source_node_id,source_tab_name,target_session_id,target_node_id,target_tab_name,created_at)
                    VALUES (?,?,?,?,?,?,?,?)
                    """, UUID.randomUUID().toString(), origin.sourceSessionId(), origin.sourceNodeId(),
                    origin.sourceTabName(), s.id(), origin.targetNodeId(), s.name(), Instant.now().toString());
            }
            return created;
        });
    }
    private void protect(String sessionId, String profileId) {
        if (profileId != null) {
            jdbc.update("INSERT OR IGNORE INTO storage_protection(profile_id,session_id) VALUES(?,?)", profileId, sessionId);
            jdbc.update("DELETE FROM storage_reclaimable WHERE session_id=?", sessionId);
        }
    }
    public Optional<Session> updateMetadata(String id, String name) {
        return tx.execute(status -> {
            if (name != null) jdbc.update("UPDATE sessions SET name=?,updated_at=? WHERE id=?", name, Instant.now().toString(), id);
            return findById(id);
        });
    }
    public Optional<Session> replaceWorkspace(String id, String sql, String dialect, String connectionId,
                                               String snapshot, String tree) {
        return tx.execute(status -> {
            int changed = jdbc.update("""
                UPDATE sessions SET sql_text=?,dialect=?,connection_id=?,workspace_snapshot_json=?,fragment_tree_json=?,updated_at=? WHERE id=?
                """, sql, dialect, connectionId, snapshot, tree, Instant.now().toString(), id);
            return changed == 0 ? Optional.empty() : findById(id);
        });
    }
    public List<SessionEditLink> editLinks(List<String> sessionIds) {
        if (sessionIds.isEmpty()) return List.of();
        String placeholders = String.join(",", Collections.nCopies(sessionIds.size(), "?"));
        List<Object> args = new ArrayList<>(sessionIds);
        args.addAll(sessionIds);
        return jdbc.query("""
            SELECT l.*,s.id IS NOT NULL AS source_alive,t.id IS NOT NULL AS target_alive,
                   COALESCE(s.name,l.source_tab_name) AS source_name,COALESCE(t.name,l.target_tab_name) AS target_name
            FROM session_edit_links l LEFT JOIN sessions s ON s.id=l.source_session_id
            LEFT JOIN sessions t ON t.id=l.target_session_id
            WHERE l.source_session_id IN (
            """ + placeholders + ") OR l.target_session_id IN (" + placeholders + ") ORDER BY l.sequence",
            (rs,n) -> new SessionEditLink(rs.getString("link_id"), rs.getLong("sequence"),
                rs.getString("source_session_id"), rs.getString("source_node_id"), rs.getString("source_name"),
                rs.getString("target_session_id"), rs.getString("target_node_id"), rs.getString("target_name"),
                rs.getString("created_at"), rs.getBoolean("source_alive"), rs.getBoolean("target_alive")), args.toArray());
    }
    private void pruneLinks() {
        jdbc.update("""
            DELETE FROM session_edit_links WHERE NOT EXISTS (SELECT 1 FROM sessions WHERE id=source_session_id)
            AND NOT EXISTS (SELECT 1 FROM sessions WHERE id=target_session_id)
            """);
    }
    public void delete(String id) {
        tx.executeWithoutResult(status -> { jdbc.update("DELETE FROM sessions WHERE id=?", id); pruneLinks(); });
    }
    public List<Session> findStale(int daysOld) {
        return jdbc.query("SELECT " + COLUMNS + " FROM sessions WHERE updated_at<? ORDER BY updated_at ASC", MAPPER,
            Instant.now().minusSeconds(daysOld * 86400L).toString());
    }
    public int deleteStale(int daysOld) {
        return tx.execute(status -> {
            jdbc.update("UPDATE storage_generation SET revision=revision+1 WHERE id=1");
            int count = jdbc.update("""
                DELETE FROM sessions WHERE julianday(updated_at)<julianday(?)
                AND EXISTS(SELECT 1 FROM storage_reclaimable r WHERE r.session_id=sessions.id)
                AND NOT EXISTS(SELECT 1 FROM storage_protection p WHERE p.session_id=sessions.id)
                """, Instant.now().minusSeconds(daysOld * 86400L).toString());
            pruneLinks();
            return count;
        });
    }
}
