package com.bitdata.sqlworkbench.storage;

import com.bitdata.sqlworkbench.config.SqliteConfig;
import com.bitdata.sqlworkbench.session.Session;
import com.bitdata.sqlworkbench.session.SessionController;
import com.bitdata.sqlworkbench.session.SessionStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.CannotCreateTransactionException;
import java.nio.file.Path;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class StorageMaintenanceHttpTest {
    @TempDir Path dir;

    @Test void exclusiveMaintenanceReturnsRetryableHttpForJdbcAndTransactionStart() throws Exception {
        var ds = new StorageDataSource();
        ds.setUrl("jdbc:sqlite:" + dir.resolve("workbench.db"));
        var jdbc = new JdbcTemplate(ds);
        ReflectionTestUtils.invokeMethod(new SqliteConfig(), "initSchema", jdbc);
        var store = new SessionStore(jdbc);
        store.save(Session.builder().id("owner").name("owner").sqlText("SELECT 1").build());
        var mvc = MockMvcBuilders.standaloneSetup(new SessionController(store, new ObjectMapper().findAndRegisterModules()))
            .setControllerAdvice(new StorageErrorAdvice()).build();
        var gate = (ReentrantReadWriteLock) ReflectionTestUtils.getField(ds, "gate");
        var worker = Executors.newSingleThreadExecutor();
        gate.writeLock().lock();
        try {
            worker.submit(() -> {
                var failure = assertThrows(CannotCreateTransactionException.class,
                    () -> store.replaceWorkspace("owner", "SELECT 2", "postgresql", null, null, null));
                assertInstanceOf(StorageDataSource.Maintenance.class, failure.getCause());
                mvc.perform(get("/api/sessions/owner"))
                    .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("STORAGE_MAINTENANCE"));
                mvc.perform(post("/api/sessions").contentType("application/json").content("""
                    {"id":"during-maintenance","name":"new","sqlText":"SELECT 4"}
                    """))
                    .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("CREATION_STORAGE_BUSY"));
                mvc.perform(put("/api/sessions/owner/workspace").contentType("application/json").content("""
                    {"sqlText":"SELECT 2","dialect":"postgresql","connectionId":null,"workspaceSnapshotJson":null}
                    """))
                    .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("STORAGE_MAINTENANCE"))
                    .andExpect(jsonPath("$.degraded").value(true));
                return null;
            }).get(10, TimeUnit.SECONDS);
        } finally {
            gate.writeLock().unlock();
            worker.shutdownNow();
        }
        assertEquals("SELECT 1", store.findById("owner").orElseThrow().sqlText());
        assertTrue(store.findById("during-maintenance").isEmpty());
        mvc.perform(put("/api/sessions/owner/workspace").contentType("application/json").content("""
            {"sqlText":"SELECT 3","dialect":"postgresql","connectionId":null,"workspaceSnapshotJson":null}
            """))
            .andExpect(status().isOk());
        assertEquals("SELECT 3", store.findById("owner").orElseThrow().sqlText());
    }
}
