package com.bitdata.sqlworkbench.optimization;

import com.bitdata.sqlworkbench.config.SqliteConfig;
import com.bitdata.sqlworkbench.session.Session;
import com.bitdata.sqlworkbench.session.SessionStore;
import com.bitdata.sqlworkbench.storage.StorageDataSource;
import com.bitdata.sqlworkbench.storage.StorageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static com.bitdata.sqlworkbench.optimization.PerformanceExperimentContract.*;
import static com.bitdata.sqlworkbench.optimization.OptimizationSql.Source;
import static org.junit.jupiter.api.Assertions.*;

class PerformanceExperimentReportStoreTest {
    @TempDir Path dir;
    JdbcTemplate jdbc;
    PerformanceExperimentReportStore reports;
    SessionStore sessions;

    @BeforeEach
    void setup() {
        var ds = new StorageDataSource();
        ds.setUrl("jdbc:sqlite:" + dir.resolve("workbench.db"));
        jdbc = new JdbcTemplate(ds);
        ReflectionTestUtils.invokeMethod(new SqliteConfig(), "initSchema", jdbc);
        reports = new PerformanceExperimentReportStore(jdbc, new ObjectMapper());
        sessions = new SessionStore(jdbc);
        sessions.save(Session.builder().id("source").name("source").sqlText("SELECT 1").dialect("postgresql").build());
    }

    private Report report(String id, String session, String completedAt) {
        var source = new Source(session, "source-rev", "SELECT o.x FROM public.a AS o", "connection", "connection-rev", "postgresql");
        var request = new Request(1, source, "SELECT o.x FROM public.a AS o", null, "seed", 30);
        var protocol = new Protocol("protocol", "seed", 2, 10, "seeded-alternating", "READ_ONLY_REPEATABLE_READ", "READ_ONLY_REPEATABLE_READ", 256);
        var validation = new Validation("MATCH", null, "EXACT_TYPED_BAG_V1", null, null, null, List.of(), false);
        var environment = new Environment(Map.of("product", "PostgreSQL"), Map.of("product", "PostgreSQL"), true, List.of());
        var summary = new Summary("COMPLETE", null, null, "0", "0", List.of("descriptive"));
        return new Report(1, id, "COMPLETED", "FINISHED", "COMPLETE", null, false, request, protocol, validation,
                environment, List.of(), summary, new Persistence("SAVED", null), null,
                "2026-10-26T00:00:00Z", "2026-10-26T00:00:01Z", completedAt);
    }

    @Test
    void saveBumpsGenerationSurvivesRestartAndUsesKeysetPaging() throws Exception {
        long before = jdbc.queryForObject("SELECT revision FROM storage_generation WHERE id=1", Long.class);
        reports.save(report("experiment-0", "source", "2000-01-01T00:00:00Z"));
        assertEquals(before + 1, jdbc.queryForObject("SELECT revision FROM storage_generation WHERE id=1", Long.class));

        for (int i = 1; i < 52; i++) reports.save(report("experiment-" + i, "source", "2000-01-01T00:00:" + String.format("%02dZ", i)));
        var page = reports.page("source", null);
        assertEquals(50, page.items().size());
        assertEquals("MATCH",page.items().get(0).resultVerdict());
        assertEquals("COMPLETE",page.items().get(0).comparisonState());
        assertNotNull(page.nextCursor());
        assertEquals(2, reports.page("source", page.nextCursor()).items().size());
        assertThrows(OptimizationFailure.class, () -> reports.page("source", "invalid"));

        var restarted = new PerformanceExperimentReportStore(jdbc, new ObjectMapper());
        assertEquals("experiment-0", restarted.find("experiment-0").path("experimentId").asText());
    }

    @Test
    void budgetFailureDoesNotWriteOrAdvanceGeneration() {
        var oversized = report("too-large", "source", "2000-01-01T00:00:00Z");
        String huge = "x".repeat(1024 * 1024);
        var request = oversized.request();
        var oversizedRequest = new Request(request.version(), request.source(), huge, request.candidateId(), request.seed(), request.timeoutSeconds());
        Report oversizedReport = new Report(oversized.version(), oversized.experimentId(), oversized.status(), oversized.phase(), oversized.comparisonState(),
                oversized.errorReason(), oversized.degraded(), oversizedRequest, oversized.protocol(), oversized.validation(), oversized.environment(),
                oversized.samples(), oversized.summary(), oversized.persistence(), oversized.actualPlans(), oversized.createdAt(), oversized.startedAt(), oversized.completedAt());
        long before = jdbc.queryForObject("SELECT revision FROM storage_generation WHERE id=1", Long.class);
        assertThrows(IllegalArgumentException.class, () -> reports.save(oversizedReport));
        assertEquals(before, jdbc.queryForObject("SELECT revision FROM storage_generation WHERE id=1", Long.class));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM optimization_experiment_reports", Integer.class));
    }

    private Report withCandidate(Report report,String sql) {
        var request=report.request();
        return new Report(report.version(),report.experimentId(),report.status(),report.phase(),report.comparisonState(),
            report.errorReason(),report.degraded(),new Request(1,request.source(),sql,null,request.seed(),request.timeoutSeconds()),
            report.protocol(),report.validation(),report.environment(),report.samples(),report.summary(),report.persistence(),
            report.actualPlans(),report.createdAt(),report.startedAt(),report.completedAt());
    }

    @ParameterizedTest
    @ValueSource(ints={1048575,1048576,1048577})
    void utf8ReportBudgetIsExactAndRejectedSaveIsAtomic(int bytes) throws Exception {
        var base=withCandidate(report("boundary","source","2026-10-26T00:00:00Z"),"");
        var mapper=new ObjectMapper();
        int overhead=mapper.writeValueAsBytes(base).length;
        // A multibyte character ensures this checks encoded bytes, not Java string length.
        var boundary=withCandidate(base,"汉"+"x".repeat(bytes-overhead-3));
        assertEquals(bytes,mapper.writeValueAsBytes(boundary).length);
        long generation=jdbc.queryForObject("SELECT revision FROM storage_generation WHERE id=1",Long.class);
        if(bytes<=1024*1024) {
            reports.save(boundary);
            assertEquals(bytes,jdbc.queryForObject("SELECT length(CAST(report_json AS BLOB)) FROM optimization_experiment_reports WHERE id='boundary'",Integer.class));
            assertEquals(generation+1,jdbc.queryForObject("SELECT revision FROM storage_generation WHERE id=1",Long.class));
        }else{
            assertThrows(IllegalArgumentException.class,()->reports.save(boundary));
            assertEquals(generation,jdbc.queryForObject("SELECT revision FROM storage_generation WHERE id=1",Long.class));
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM optimization_experiment_reports",Integer.class));
        }
    }

    @Test
    void capacityCleanupReclaimsFreshReportsWithoutTouchingProtectedOrUnknownOwners() throws Exception {
        for(String id:List.of("protected","unknown","reclaim"))
            sessions.save(Session.builder().id(id).name(id).sqlText("SELECT 1").dialect("postgresql").build());
        String now=java.time.Instant.now().toString();
        for(String owner:List.of("protected","unknown","reclaim")){
            int count=owner.equals("protected")?8:owner.equals("unknown")?4:12;
            for(int n=0;n<count;n++)reports.save(withCandidate(report(owner+n,owner,now),"x".repeat(850000)));
        }
        var storage=new StorageService(jdbc);
        try{
            storage.protect("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",List.of("protected"));
            storage.reclaimable(List.of("reclaim"),storage.preview().revision());
            var policy=storage.apply(false,365,16,storage.policy().revision());
            assertEquals(0,storage.preview().expiredCounts().get("optimization_experiment_reports"));
            assertTrue(storage.measure().totalBytes()>16L*1024*1024);
            var initial=storage.start(policy.revision(),"manual");
            StorageService.Job job;long end=System.nanoTime()+Duration.ofSeconds(30).toNanos();
            do{job=storage.get(initial.id());if(!List.of("queued","running").contains(job.status()))break;Thread.sleep(10);}while(System.nanoTime()<end);
            assertEquals("completed",job.status(),job.errorReason());assertTrue(job.targetMet());
            assertTrue(job.deleted().getOrDefault("optimization_experiment_reports",0)>0);
            assertEquals(8,jdbc.queryForObject("SELECT count(*) FROM optimization_experiment_reports WHERE session_id='protected'",Integer.class));
            assertEquals(4,jdbc.queryForObject("SELECT count(*) FROM optimization_experiment_reports WHERE session_id='unknown'",Integer.class));
            assertTrue(jdbc.queryForObject("SELECT count(*) FROM optimization_experiment_reports WHERE session_id='reclaim'",Integer.class)<12);
        }finally{storage.close();}
    }

    @Test
    void sessionDeleteRemovesHistoryAndLateSaveIsRejected() throws Exception {
        reports.save(report("experiment-1", "source", "2000-01-01T00:00:00Z"));
        long beforeDelete = jdbc.queryForObject("SELECT revision FROM storage_generation WHERE id=1", Long.class);
        sessions.delete("source");
        assertTrue(jdbc.queryForObject("SELECT revision FROM storage_generation WHERE id=1", Long.class) > beforeDelete);
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM optimization_experiment_reports", Integer.class));
        assertThrows(OptimizationFailure.class, () -> reports.find("experiment-1"));
        var failure = assertThrows(OptimizationFailure.class, () -> reports.save(report("late", "source", "2000-01-01T00:00:01Z")));
        assertEquals("SESSION_GONE", failure.reason);
    }

    @Test
    void futureVersionRemainsRawAndIsUnknownInHistory() {
        jdbc.update("INSERT INTO optimization_experiment_reports VALUES(?,?,?,?,?)", "future", "source", 99,
                "{\"version\":99,\"experimentId\":\"future\",\"future\":true}", "2000-01-01T00:00:00Z");
        var item = reports.page("source", null).items().get(0);
        assertEquals(99, item.version());
        assertEquals("UNKNOWN", item.resultVerdict());
        assertTrue(reports.find("future").path("future").asBoolean());
    }

    @Test
    void protectedAndUnknownOwnersAreExcludedWhileReclaimableAndOrphanReportsAreEligible() throws Exception {
        for(String id:List.of("protected","unknown","reclaim"))
            sessions.save(Session.builder().id(id).name(id).sqlText("SELECT 1").dialect("postgresql").build());
        for(String id:List.of("protected","unknown","reclaim"))reports.save(report("report-"+id,id,"2000-01-01T00:00:00Z"));
        jdbc.update("INSERT INTO optimization_experiment_reports VALUES(?,?,?,?,?)","orphan","absent",1,"{}","2000-01-01T00:00:00Z");
        var storage=new StorageService(jdbc);
        try {
            storage.protect("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",List.of("protected"));
            storage.reclaimable(List.of("reclaim"),storage.preview().revision());
            assertEquals(2,storage.preview().expiredCounts().get("optimization_experiment_reports"));
            assertEquals(1,storage.preview().protectedSessions());
            var initial=storage.start(storage.policy().revision(),"manual");
            StorageService.Job job;long end=System.nanoTime()+Duration.ofSeconds(20).toNanos();
            do{job=storage.get(initial.id());if(!List.of("queued","running").contains(job.status()))break;Thread.sleep(10);}while(System.nanoTime()<end);
            assertEquals("completed",job.status());assertEquals(2,job.deleted().get("optimization_experiment_reports"));
            assertNotNull(reports.find("report-protected"));assertNotNull(reports.find("report-unknown"));
            assertThrows(OptimizationFailure.class,()->reports.find("report-reclaim"));assertThrows(OptimizationFailure.class,()->reports.find("orphan"));
        }finally{storage.close();}
    }

    @Test
    void retentionCountsAndCleanupIncludeExperimentReports() throws Exception {
        reports.save(report("old", "source", "2000-01-01T00:00:00Z"));
        var storage = new StorageService(jdbc);
        try {
            storage.protect("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", List.of());
            storage.reclaimable(List.of("source"), storage.preview().revision());
            assertEquals(1, storage.preview().expiredCounts().get("optimization_experiment_reports"));
            var initial = storage.start(storage.policy().revision(), "manual");
            StorageService.Job job;
            long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            do {
                job = storage.get(initial.id());
                if (!List.of("queued", "running").contains(job.status())) break;
                Thread.sleep(10);
            } while (System.nanoTime() < deadline);
            assertEquals("completed", job.status());
            assertEquals(1, job.deleted().get("optimization_experiment_reports"));
            assertThrows(OptimizationFailure.class, () -> reports.find("old"));
        } finally {
            storage.close();
        }
    }
}
