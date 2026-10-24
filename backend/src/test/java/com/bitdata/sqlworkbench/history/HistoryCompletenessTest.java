package com.bitdata.sqlworkbench.history;

import com.bitdata.sqlworkbench.config.SqliteConfig;
import com.bitdata.sqlworkbench.execution.model.ResultCompleteness;
import com.bitdata.sqlworkbench.storage.StorageService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.Path;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

class HistoryCompletenessTest {
    @TempDir Path dir;
    JdbcTemplate jdbc;
    @BeforeEach void setup() {
        jdbc=new JdbcTemplate(new DriverManagerDataSource("jdbc:sqlite:"+dir.resolve("history.db")));
        jdbc.execute("CREATE TABLE history(id TEXT PRIMARY KEY,session_id TEXT NOT NULL,sql_text TEXT NOT NULL,executed_at TEXT NOT NULL,duration_ms INTEGER,row_count INTEGER)");
        jdbc.update("INSERT INTO history VALUES('old','s','SELECT 1',?,2,1)",Instant.now().toString());
        migrate(); migrate();
    }
    void migrate() { ReflectionTestUtils.invokeMethod(new SqliteConfig(),"initSchema",jdbc); }
    @Test void oldRowsStayUnknownAndMigrationIsIdempotent() {
        var old=new HistoryStore(jdbc).findRecent(5).get(0);
        assertEquals("COMPLETENESS_UNVERIFIED",old.resultCompleteness().errorReason());
        assertNull(jdbc.queryForMap("SELECT result_completeness_json FROM history WHERE id='old'").get("result_completeness_json"));
    }
    @Test void completenessSurvivesBothColdReadPathsAndReplayCannotEraseIt() {
        var store=new HistoryStore(jdbc);
        var entry=new HistoryEntry("new","s","SELECT 1",Instant.now(),3,1,new ResultCompleteness(ResultCompleteness.State.TRUNCATED,1,null));
        store.save(entry); store.save(entry);
        assertEquals(entry,new HistoryStore(jdbc).findRecent(1).get(0));
        assertEquals(entry,new HistoryStore(jdbc).findBySessionId("s",1).get(0));
        assertEquals("HISTORY_CONFLICT",assertThrows(StorageService.Failure.class,() -> store.save(
            new HistoryEntry("new","s","SELECT 1",Instant.now(),3,1))).code);
    }
    @Test void damagedMetadataIsObservable() {
        jdbc.update("UPDATE history SET result_completeness_json='broken' WHERE id='old'");
        assertEquals("HISTORY_COMPLETENESS_INVALID",new HistoryStore(jdbc).findRecent(1).get(0).resultCompleteness().errorReason());
    }
}
