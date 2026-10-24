package com.bitdata.sqlworkbench.session;

import com.bitdata.sqlworkbench.config.SqliteConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.dao.DataAccessResourceFailureException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SessionCreationRetryTest {
    @TempDir Path dir;
    JdbcTemplate jdbc;
    SessionStore store;
    SessionController controller;
    ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    @BeforeEach void setup() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:sqlite:" + dir.resolve("workbench.db")));
        ReflectionTestUtils.invokeMethod(new SqliteConfig(), "initSchema", jdbc);
        store = new SessionStore(jdbc);
        controller = new SessionController(store, mapper);
    }
    SessionController.CreateSessionRequest request(String key, String sql, SessionEditLink.Origin origin) {
        return new SessionController.CreateSessionRequest(null, "Edited", sql, "postgresql", null, null, null, origin, key);
    }
    int count(String table) { return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class); }
    String key() { return UUID.randomUUID().toString(); }
    SessionEditLink.Origin origin() { return new SessionEditLink.Origin("source", "node", "Original", "edited"); }
    void error(String code, Runnable action) {
        assertEquals(code, assertThrows(SessionStore.CreationFailure.class, action::run).code);
    }
    @Test void replaysOneSessionAndOriginAndReturnsCurrentWorkspace() {
        var req = request(key(), "SELECT 2", origin());
        var first = controller.create(req);
        store.updateMetadata(first.id(), "Renamed");
        store.replaceWorkspace(first.id(), "SELECT 3", "mysql", null, "saved-graph", "saved-tree");
        var replay = controller.create(req);
        assertEquals(first.id(), replay.id());
        assertEquals(first.createdAt(), replay.createdAt());
        assertEquals("Renamed", replay.name());
        assertEquals("SELECT 3", replay.sqlText());
        assertEquals("saved-graph", replay.workspaceSnapshotJson());
        assertEquals(1, count("sessions"));
        assertEquals(1, count("session_edit_links"));
        assertEquals(1, count("session_creation_receipts"));
    }
    @Test void differentKeysAllowIdenticalSqlAndLegacyRequestsKeepWorking() {
        var a = controller.create(request(key(), "SELECT 2", null));
        var b = controller.create(request(key(), "SELECT 2", null));
        assertNotEquals(a.id(), b.id());
        controller.create(request(null, "SELECT 2", null));
        controller.create(request(null, "SELECT 2", null));
        assertEquals(4, count("sessions"));
        assertEquals(2, count("session_creation_receipts"));
    }
    @Test void conflictsOnChangedSqlDialectOriginAndExplicitId() {
        var req = request(key(), "SELECT 2", origin());
        var first = controller.create(req);
        error("CREATION_REQUEST_CONFLICT", () -> controller.create(request(req.creationRequestId(), "SELECT 3", origin())));
        error("CREATION_REQUEST_CONFLICT", () -> controller.create(new SessionController.CreateSessionRequest(null, "Edited", "SELECT 2", "mysql", null, null, null, origin(), req.creationRequestId())));
        error("CREATION_REQUEST_CONFLICT", () -> controller.create(request(req.creationRequestId(), "SELECT 2", null)));
        error("CREATION_REQUEST_CONFLICT", () -> controller.create(new SessionController.CreateSessionRequest(first.id(), "Edited", "SELECT 2", "postgresql", null, null, null, origin(), req.creationRequestId())));
        assertEquals(1, count("sessions"));
        assertEquals(1, count("session_edit_links"));
    }
    @Test void defaultsAndJsonPropertyOrderHaveStableFingerprints() throws Exception {
        String key = key();
        var first = controller.create(new SessionController.CreateSessionRequest(null, null, null, null, null, null, null, null, key));
        var explicit = new SessionController.CreateSessionRequest(null, "Untitled", "", "postgresql", null, null, null, null, key);
        assertEquals(first.id(), controller.create(explicit).id());
        var reordered = mapper.readValue("{\"dialect\":\"postgresql\",\"sqlText\":\"\",\"name\":\"Untitled\",\"creationRequestId\":\"" + key + "\"}", SessionController.CreateSessionRequest.class);
        assertEquals(first.id(), controller.create(reordered).id());
        // Durable v1 contract: defaults, field order and UTF-8 must not drift on upgrade.
        assertEquals("v1:eddf2ffca2c593aa24ab02ed5d13a2b916582a8e47c27164200ae487bdec0223",
            jdbc.queryForObject("SELECT request_fingerprint FROM session_creation_receipts", String.class));
    }
    @Test void deletedAndTtlTargetsNeverReappear() {
        var req = request(key(), "SELECT 2", origin());
        store.delete(controller.create(req).id());
        error("CREATION_TARGET_GONE", () -> controller.create(req));
        var ttl = request(key(), "SELECT 3", null);
        var target = controller.create(ttl);
        jdbc.update("UPDATE sessions SET updated_at='2000-01-01T00:00:00Z' WHERE id=?", target.id());
        jdbc.update("INSERT INTO storage_reclaimable VALUES(?)",target.id());
        store.deleteStale(30);
        error("CREATION_TARGET_GONE", () -> controller.create(ttl));
        assertEquals(0, count("sessions"));
        assertEquals(2, count("session_creation_receipts"));
    }
    @Test void originFailureRollsBackReceiptAndAllowsSameKeyRecovery() {
        jdbc.execute("CREATE TRIGGER reject_link BEFORE INSERT ON session_edit_links BEGIN SELECT RAISE(ABORT,'injected'); END");
        var req = request(key(), "SELECT 2", origin());
        assertThrows(RuntimeException.class, () -> controller.create(req));
        assertEquals(0, count("sessions"));
        assertEquals(0, count("session_creation_receipts"));
        jdbc.execute("DROP TRIGGER reject_link");
        assertEquals(controller.create(req).id(), controller.create(req).id());
    }
    @Test void newStoreAndRepeatedMigrationKeepReceipts() {
        var legacy = controller.create(request(null, "SELECT 1", null));
        jdbc.execute("DROP TABLE session_creation_receipts"); // Simulate the pre-receipt schema.
        ReflectionTestUtils.invokeMethod(new SqliteConfig(), "initSchema", jdbc);
        ReflectionTestUtils.invokeMethod(new SqliteConfig(), "initSchema", jdbc);
        assertEquals("SELECT 1", store.findById(legacy.id()).orElseThrow().sqlText());
        var req = request(key(), "SELECT 2", origin());
        var first = controller.create(req);
        ReflectionTestUtils.invokeMethod(new SqliteConfig(), "initSchema", jdbc);
        ReflectionTestUtils.invokeMethod(new SqliteConfig(), "initSchema", jdbc);
        var reopened = new SessionController(new SessionStore(jdbc), mapper).create(req);
        assertEquals(first.id(), reopened.id());
        assertEquals(1, count("session_creation_receipts"));
    }
    @Test void explicitSessionIdCollisionRollsBackTheNewReceipt() {
        var first = controller.create(request(key(), "SELECT 1", null));
        error("CREATION_REQUEST_CONFLICT", () -> controller.create(new SessionController.CreateSessionRequest(
            first.id(), "Another", "SELECT 2", "postgresql", null, null, null, null, key())));
        error("CREATION_REQUEST_CONFLICT", () -> controller.create(new SessionController.CreateSessionRequest(
            first.id(), "Another", "SELECT 2", "postgresql", null, null, null, null)));
        assertEquals(1, count("sessions"));
        assertEquals(1, count("session_creation_receipts"));
        assertEquals("SELECT 1", store.findById(first.id()).orElseThrow().sqlText());
    }
    @Test void concurrentRequestsCommitOnlyOneTarget() throws Exception {
        var req = request(key(), "SELECT 2", origin());
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(6);
        try {
            var calls = new ArrayList<Future<String>>();
            for (int i = 0; i < 6; i++) calls.add(pool.submit(() -> {
                start.await();
                try { return controller.create(req).id(); }
                catch (SessionStore.CreationFailure e) { assertEquals("CREATION_STORAGE_BUSY", e.code); return null; }
            }));
            start.countDown();
            var ids = new HashSet<String>();
            for (var call : calls) { var id = call.get(15, TimeUnit.SECONDS); if (id != null) ids.add(id); }
            ids.add(controller.create(req).id());
            assertEquals(1, ids.size());
            assertEquals(1, count("sessions"));
            assertEquals(1, count("session_edit_links"));
            assertEquals(1, count("session_creation_receipts"));
        } finally { pool.shutdownNow(); }
    }
    @Test void httpErrorsAreStructuredAndSuccessfulReplayKeepsSessionShape() throws Exception {
        MockMvc http = MockMvcBuilders.standaloneSetup(controller).build();
        var req = request(key(), "SELECT 2", null);
        var first = controller.create(req);
        http.perform(post("/api/sessions").contentType("application/json").content(mapper.writeValueAsBytes(req)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(first.id())).andExpect(jsonPath("$.sqlText").value("SELECT 2"));
        http.perform(post("/api/sessions").contentType("application/json").content(mapper.writeValueAsBytes(request(req.creationRequestId(), "SELECT 3", null))))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CREATION_REQUEST_CONFLICT"));
        store.delete(first.id());
        http.perform(post("/api/sessions").contentType("application/json").content(mapper.writeValueAsBytes(req)))
            .andExpect(status().isGone()).andExpect(jsonPath("$.code").value("CREATION_TARGET_GONE"));
        for (String invalid : List.of("", "bad-key")) http.perform(post("/api/sessions").contentType("application/json").content(mapper.writeValueAsBytes(request(invalid, "SELECT 2", null))))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("CREATION_VALIDATION_ERROR"));
        http.perform(post("/api/sessions").contentType("application/json").content("{invalid"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").isString());
    }
    @Test void storageBusyHasAnObservableRecoverableResponse() throws Exception {
        var busyStore = mock(SessionStore.class);
        when(busyStore.createWithOrigin(any(), any(), any(), any(), any())).thenThrow(
            new DataAccessResourceFailureException("busy", new SQLException("database locked", "", 5)));
        MockMvcBuilders.standaloneSetup(new SessionController(busyStore, mapper)).build()
            .perform(post("/api/sessions").contentType("application/json").content(mapper.writeValueAsBytes(request(key(), "SELECT 2", null))))
            .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("CREATION_STORAGE_BUSY"));
    }
}
