package com.bitdata.sqlworkbench.session;

import com.bitdata.sqlworkbench.config.SqliteConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class WorkspacePersistenceTest {
    @TempDir Path dir;
    JdbcTemplate jdbc;
    SessionStore store;
    @BeforeEach void setup() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:sqlite:" + dir.resolve("workbench.db")));
        ReflectionTestUtils.invokeMethod(new SqliteConfig(), "initSchema", jdbc);
        store = new SessionStore(jdbc);
    }
    Session create(String id) { return store.save(Session.builder().id(id).name(id).sqlText("SELECT 1").build()); }
    SessionEditLink.Origin origin() { return new SessionEditLink.Origin("source", "same-node", "source", "edited-node"); }

    @Test void migrationPreservesLegacyDataAndIsIdempotent() {
        jdbc.execute("DROP TABLE sessions");
        jdbc.execute("CREATE TABLE sessions (id TEXT PRIMARY KEY,name TEXT NOT NULL,sql_text TEXT NOT NULL,dialect TEXT NOT NULL,connection_id TEXT,fragment_tree_json TEXT,created_at TEXT NOT NULL,updated_at TEXT NOT NULL)");
        jdbc.update("INSERT INTO sessions VALUES ('legacy','legacy','SELECT 1','postgresql',null,null,'2026-10-12T00:00:00Z','2026-10-12T00:00:00Z')");
        ReflectionTestUtils.invokeMethod(new SqliteConfig(), "initSchema", jdbc);
        ReflectionTestUtils.invokeMethod(new SqliteConfig(), "initSchema", jdbc);
        assertEquals("SELECT 1", store.findById("legacy").orElseThrow().sqlText());
        assertEquals(1, jdbc.queryForList("PRAGMA table_info(sessions)").stream().filter(c -> "workspace_snapshot_json".equals(c.get("name"))).count());
    }
    @Test void sqlSnapshotAndTreeReplaceTogetherAndNeverResurrect() {
        create("s");
        store.replaceWorkspace("s", "SELECT 2", "postgresql", null, "snapshot", "tree");
        var reopened = new SessionStore(jdbc).findById("s").orElseThrow();
        assertEquals("SELECT 2", reopened.sqlText());
        assertEquals("snapshot", reopened.workspaceSnapshotJson());
        assertEquals("tree", reopened.fragmentTreeJson());
        store.replaceWorkspace("s", "", "postgresql", null, null, null);
        assertNull(store.findById("s").orElseThrow().fragmentTreeJson());
        store.delete("s");
        assertTrue(store.replaceWorkspace("s", "late", "postgresql", null, null, null).isEmpty());
    }
    @Test void originInsertFailureRollsBackTargetCreation() {
        create("source");
        jdbc.execute("CREATE TRIGGER reject_link BEFORE INSERT ON session_edit_links BEGIN SELECT RAISE(ABORT,'injected'); END");
        assertThrows(Exception.class, () -> store.createWithOrigin(Session.builder().id("target").name("target").build(), origin()));
        assertTrue(store.findById("target").isEmpty());
        assertTrue(store.editLinks(List.of("source")).isEmpty());
    }
    @Test void deletionAndTtlRetainSurvivingEndpointAndPruneBothGone() {
        create("source");
        store.createWithOrigin(Session.builder().id("target").name("target").build(), origin());
        store.delete("source");
        var links = store.editLinks(List.of("target"));
        assertEquals(1, links.size());
        assertFalse(links.get(0).sourceAlive());
        assertEquals("source", links.get(0).sourceTabName());
        jdbc.update("UPDATE sessions SET updated_at='2000-01-01T00:00:00Z' WHERE id='target'");
        assertEquals(0, store.deleteStale(30), "Unknown restored work must be retained");
        jdbc.update("INSERT INTO storage_reclaimable VALUES('target')");
        assertEquals(1, store.deleteStale(30));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM session_edit_links", Integer.class));
        assertTrue(store.editLinks(List.of()).isEmpty());
    }
    @Test void sameNodeIdsAcrossSessionsRemainIndependentAndNameUpdatesPreserveGraph() {
        create("source");
        store.createWithOrigin(Session.builder().id("t1").name("t1").build(), origin());
        store.createWithOrigin(Session.builder().id("t2").name("t2").build(), origin());
        assertEquals(2, store.editLinks(List.of("source")).size());
        store.replaceWorkspace("t1", "SELECT 2", "postgresql", null, "snapshot", "tree");
        store.updateMetadata("t1", "renamed");
        assertEquals("snapshot", store.findById("t1").orElseThrow().workspaceSnapshotJson());
        assertEquals("renamed", store.editLinks(List.of("t1")).get(0).targetTabName());
    }
    @Test void endpointRequiresFullReplacementAndRejectsUnknownVersion() throws Exception {
        var mapper = new ObjectMapper();
        var controller = new SessionController(store, mapper);
        create("s");
        assertThrows(ResponseStatusException.class, () -> controller.workspace("s", mapper.readTree("{\"sqlText\":\"\"}")));
        assertThrows(ResponseStatusException.class, () -> controller.workspace("s", mapper.valueToTree(java.util.Map.of(
            "sqlText", "x", "dialect", "postgresql", "connectionId", "", "workspaceSnapshotJson", "{\"version\":99}"))));
    }
}
