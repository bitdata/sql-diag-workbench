package com.bitdata.sqlworkbench.session;

import com.bitdata.sqlworkbench.config.SqliteConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class SessionCleanupSafetyTest {
    @TempDir Path dir;
    @Test void oldWorkspaceWithoutAuthoritativeProtectionInventoryMustSurviveTtl() {
        var jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:sqlite:" + dir.resolve("workbench.db")));
        ReflectionTestUtils.invokeMethod(new SqliteConfig(), "initSchema", jdbc);
        var store = new SessionStore(jdbc);
        var saved = store.save(Session.builder().id("restorable").name("Saved work").sqlText("SELECT 1").dialect("postgresql").build());
        jdbc.update("UPDATE sessions SET updated_at='2000-01-01T00:00:00Z' WHERE id=?", saved.id());
        assertEquals(0, store.deleteStale(30), "A missing renderer inventory is not permission to delete a saved workspace");
        assertTrue(store.findById(saved.id()).isPresent());
    }
}
