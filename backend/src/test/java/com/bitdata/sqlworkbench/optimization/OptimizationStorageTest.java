package com.bitdata.sqlworkbench.optimization;

import com.bitdata.sqlworkbench.config.SqliteConfig;
import com.bitdata.sqlworkbench.storage.*;
import com.bitdata.sqlworkbench.session.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import static com.bitdata.sqlworkbench.optimization.OptimizationSql.*;
import static org.junit.jupiter.api.Assertions.*;

class OptimizationStorageTest {
    @TempDir Path dir;
    JdbcTemplate jdbc; OptimizationReportStore reports; SessionStore sessions;
    @BeforeEach void setup() {
        var ds=new StorageDataSource();ds.setUrl("jdbc:sqlite:"+dir.resolve("workbench.db"));jdbc=new JdbcTemplate(ds);
        ReflectionTestUtils.invokeMethod(new SqliteConfig(),"initSchema",jdbc);
        reports=new OptimizationReportStore(jdbc,new ObjectMapper());sessions=new SessionStore(jdbc);
        for(String id:List.of("source","protected"))sessions.save(Session.builder().id(id).name(id).sqlText("SELECT 1").dialect("postgresql").build());
    }
    Report report(String id,String session) {
        return new Report(1,id,"COMPLETED","INCONCLUSIVE","TYPE_UNSUPPORTED",true,new Source(session,"rev","SELECT o.x FROM public.a AS o","connection","hash","postgresql"),"SELECT o.x FROM public.a AS o",null,null,"EXACT_TYPED_BAG_V1",null,null,null,List.of(),false,"2000-01-01T00:00:00Z","2000-01-01T00:00:01Z",new Persistence("SAVED",null));
    }
    @Test void idempotentSchemaColdReadPagingAndIndependentSessionDeletion() throws Exception {
        reports.save(report("id","source"));String trigger=jdbc.queryForObject("SELECT sql FROM sqlite_master WHERE name='session_storage_delete'",String.class);
        ReflectionTestUtils.invokeMethod(new SqliteConfig(),"initSchema",jdbc);
        assertEquals(trigger,jdbc.queryForObject("SELECT sql FROM sqlite_master WHERE name='session_storage_delete'",String.class));
        var ds=new StorageDataSource();ds.setUrl("jdbc:sqlite:"+dir.resolve("workbench.db"));var restarted=new OptimizationReportStore(new JdbcTemplate(ds),new ObjectMapper());
        assertEquals("id",restarted.find("id").path("validationId").asText());
        for(int n=0;n<52;n++)reports.save(report("id-"+n,"source"));
        var page=reports.page("source",null);assertEquals(50,page.items().size());assertNotNull(page.nextCursor());assertEquals(3,reports.page("source",page.nextCursor()).items().size());
        assertThrows(OptimizationFailure.class,()->reports.page("source","invalid"));
        jdbc.update("DELETE FROM sessions WHERE id='source'");assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM optimization_validation_reports",Integer.class));
        assertEquals("SESSION_GONE",assertThrows(OptimizationFailure.class,()->reports.save(report("late","source"))).reason);
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM sessions WHERE id='source'",Integer.class));
    }
    @Test void cleanupIncludesReportsAndKeepsProtectedHistory() throws Exception {
        reports.save(report("old","source"));reports.save(report("safe","protected"));
        var storage=new StorageService(jdbc);try {
            storage.protect("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",List.of("protected"));
            storage.reclaimable(List.of("source"),storage.preview().revision());
            assertEquals(1,storage.preview().expiredCounts().get("optimization_validation_reports"));
            var initial=storage.start(storage.policy().revision(),"manual");var job=initial;
            long deadline=System.nanoTime()+Duration.ofSeconds(20).toNanos();while(List.of("queued","running").contains(job.status())&&System.nanoTime()<deadline){Thread.sleep(10);job=storage.get(initial.id());}
            assertEquals(1,job.deleted().get("optimization_validation_reports"));assertEquals("safe",reports.find("safe").path("validationId").asText());
            assertThrows(OptimizationFailure.class,()->reports.find("old"));
        } finally {storage.close();}
    }
    @Test void futureHistoryVersionRemainsUnknownAndRawDetailIsPreserved() {
        jdbc.update("INSERT INTO optimization_validation_reports VALUES(?,?,?,?,?)","future","source",2,"{\"version\":2,\"future\":true}","2000-01-01T00:00:01Z");
        var item=reports.page("source",null).items().get(0);
        assertEquals(2,item.version());assertEquals("UNKNOWN",item.verdict());assertEquals("",item.originalPreview());
        assertTrue(reports.find("future").path("future").asBoolean());
    }
}
