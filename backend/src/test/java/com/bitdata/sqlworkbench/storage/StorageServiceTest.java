package com.bitdata.sqlworkbench.storage;

import com.bitdata.sqlworkbench.config.SqliteConfig;
import com.bitdata.sqlworkbench.session.*;
import com.bitdata.sqlworkbench.history.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class StorageServiceTest {
    @TempDir Path dir;
    StorageDataSource ds; JdbcTemplate jdbc; StorageService service; SessionStore sessions;
    static final String PROFILE="aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
    @BeforeEach void setup() {
        ds=new StorageDataSource(); ds.setUrl("jdbc:sqlite:"+dir.resolve("workbench.db"));
        jdbc=new JdbcTemplate(ds); jdbc.execute("PRAGMA journal_mode=WAL");
        ReflectionTestUtils.invokeMethod(new SqliteConfig(),"initSchema",jdbc);
        service=new StorageService(jdbc,Clock.fixed(Instant.parse("2026-10-13T00:00:00Z"),ZoneOffset.UTC));
        sessions=new SessionStore(jdbc);
    }
    @AfterEach void close() { service.close(); }
    void planReceipt(String id,boolean finalized,String expiry) {
        jdbc.update("INSERT INTO plan_observation_receipts(id,execution_id,connection_id,dialect,sql_sha256,observation_sha256,payload_json,payload_sha256,byte_size,created_at,expires_at,finalized_snapshot_id) VALUES(?,?,'c','postgresql','hash','hash','{}','hash',2,'2026-10-12T23:00:00Z',?,?)",id,id,expiry,finalized?"saved":null);
    }
    @Test void receiptExpiryIsBatchedObservableAndIndependentOfGeneralPolicy() {
        assertFalse(service.policy().enabled());
        for(int i=0;i<201;i++) planReceipt("expired-"+i,i%2==0,"2026-10-12T23:59:59Z");
        planReceipt("live",false,"2026-10-13T23:00:00Z");
        assertEquals(201L,service.status().get("expiredReceiptsPending"));
        service.expireReceipts(); assertEquals(200L,service.status().get("expiredReceiptsDeleted"));
        assertEquals(1L,service.status().get("expiredReceiptsPending"));
        service.expireReceipts(); assertEquals(201L,service.status().get("expiredReceiptsDeleted"));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM plan_observation_receipts",Integer.class));
    }
    @Test void receiptCleanupFailureCannotHideBehindHealthyStorageStatus() {
        service.protect(PROFILE,List.of()); planReceipt("expired",false,"2026-10-12T23:59:59Z");
        jdbc.execute("CREATE TRIGGER fail_receipt_cleanup BEFORE DELETE ON plan_observation_receipts BEGIN SELECT RAISE(ABORT,'injected cleanup failure'); END");
        service.expireReceipts();
        assertEquals(true,service.status().get("degraded"));
        assertEquals("SNAPSHOT_RECEIPT_EXPIRY_FAILED",service.status().get("errorReason"));
        assertEquals(1L,service.status().get("expiredReceiptsPending"));
        jdbc.execute("DROP TRIGGER fail_receipt_cleanup"); service.expireReceipts();
        assertNull(service.status().get("receiptCleanupError")); assertEquals(false,service.status().get("degraded"));
    }
    @Test void capacityCandidatesExcludeFinalizedReceiptsAndPinnedSnapshots() {
        service.protect(PROFILE,List.of());
        planReceipt("unsealed",false,"2026-10-13T23:00:00Z"); planReceipt("sealed",true,"2026-10-13T23:00:00Z");
        for(String id:List.of("pinned","free")) jdbc.update("INSERT INTO investigation_snapshots(id,version,source_observation_id,created_at,snapshot_json,payload_sha256,byte_size) VALUES(?,1,?,'2026-10-12T23:00:00Z','{}','hash',2)",id,id);
        jdbc.update("INSERT INTO investigation_snapshot_protection VALUES('pinned','2026-10-13T00:00:00Z')");
        var p=service.policy(); var size=service.measure();
        ReflectionTestUtils.setField(service,"job",new StorageService.Job("test","manual","running","capacity",p.revision(),Map.of(),size,null,false,false,null,List.of()));
        ReflectionTestUtils.invokeMethod(service,"deleteBatch","plan_observation_receipts",p,false);
        ReflectionTestUtils.invokeMethod(service,"deleteBatch","investigation_snapshots",p,false);
        assertEquals(List.of("sealed"),jdbc.queryForList("SELECT id FROM plan_observation_receipts",String.class));
        assertEquals(List.of("pinned"),jdbc.queryForList("SELECT id FROM investigation_snapshots",String.class));
        assertFalse(service.measure().degraded());
    }
    @Test void snapshotPinAndCapacityDeletionHaveASerialOutcome() throws Exception {
        service.protect(PROFILE,List.of());
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
        var snapshots=new com.bitdata.sqlworkbench.execution.InvestigationSnapshotStore(jdbc,mapper,
                new com.bitdata.sqlworkbench.execution.PlanObservationReceiptStore(jdbc,mapper),null);
        var p=service.policy(); var size=service.measure();
        ReflectionTestUtils.setField(service,"job",new StorageService.Job("race","manual","running","capacity",p.revision(),Map.of(),size,null,false,false,null,List.of()));
        var executor=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            for(int i=0;i<10;i++) {
                String id="snapshot-race-"+i;
                jdbc.update("INSERT INTO investigation_snapshots(id,version,source_observation_id,created_at,snapshot_json,payload_sha256,byte_size) VALUES(?,1,?,'2026-10-12T23:00:00Z','{}','hash',2)",id,id);
                var start=new java.util.concurrent.CountDownLatch(1);
                var pin=executor.submit(()->{ start.await(); try { snapshots.protect(id,true); return true; }
                    catch(com.bitdata.sqlworkbench.execution.InvestigationSnapshotStore.Failure gone) { assertEquals("SNAPSHOT_UNAVAILABLE",gone.code); return false; } });
                var delete=executor.submit(()->{ start.await(); ReflectionTestUtils.invokeMethod(service,"deleteBatch","investigation_snapshots",p,false); return true; });
                start.countDown(); boolean pinned=pin.get(); delete.get();
                assertEquals(pinned?1:0,jdbc.queryForObject("SELECT count(*) FROM investigation_snapshots WHERE id=?",Integer.class,id));
                assertEquals(pinned?1:0,jdbc.queryForObject("SELECT count(*) FROM investigation_snapshot_protection WHERE snapshot_id=?",Integer.class,id));
            }
        } finally { executor.shutdownNow(); }
    }
    Session session(String id) { return Session.builder().id(id).name(id).sqlText("SELECT 1").dialect("postgresql").build(); }
    void old(String id) { jdbc.update("UPDATE sessions SET updated_at='2000-01-01T00:00:00Z' WHERE id=?",id); }
    StorageService.Job finish(StorageService.Job initial) throws Exception {
        long deadline=System.nanoTime()+Duration.ofSeconds(30).toNanos();
        StorageService.Job job;
        do { job=service.get(initial.id()); if(!List.of("queued","running").contains(job.status())) return job; Thread.sleep(10); } while(System.nanoTime()<deadline);
        throw new AssertionError("Cleanup failed to finish: "+job);
    }
    @Test void policyDefaultsDisabledAndConflictsAreExplicit() {
        assertFalse(service.policy().enabled()); assertEquals(30,service.policy().retentionDays());
        assertEquals("PROTECTION_UNCONFIRMED",assertThrows(StorageService.Failure.class,()->service.start(1,"manual")).code);
        assertEquals(2,service.apply(true,7,16,1).revision());
        assertEquals("POLICY_CONFLICT",assertThrows(StorageService.Failure.class,()->service.apply(false,30,500,1)).code);
        ReflectionTestUtils.invokeMethod(new SqliteConfig(),"initSchema",jdbc);
        assertEquals(7,service.policy().retentionDays());
    }
    @Test void protectionsAreDurableAppendOnlyAndUnknownWorkspacesSurvive() throws Exception {
        sessions.save(session("protected")); sessions.save(session("unknown")); sessions.save(session("reclaim"));
        for(String id:List.of("protected","unknown","reclaim")) old(id);
        service.protect(PROFILE,List.of("protected")); service.protect(PROFILE,List.of());
        service.reclaimable(List.of("reclaim"),service.preview().revision());
        var job=finish(service.start(1,"manual"));
        assertEquals("completed",job.status()); assertTrue(sessions.findById("protected").isPresent());
        assertTrue(sessions.findById("unknown").isPresent()); assertTrue(sessions.findById("reclaim").isEmpty());
        try(var restarted=newService()) {
            assertEquals(1,restarted.preview().protectedSessions());
            assertEquals("PROTECTION_UNCONFIRMED",assertThrows(StorageService.Failure.class,()->restarted.start(1,"manual")).code);
        }
    }
    // A scoped restart avoids leaving scheduler threads alive in tests.
    AutoService newService() { return new AutoService(jdbc); }
    static class AutoService extends StorageService implements AutoCloseable { AutoService(JdbcTemplate jdbc) { super(jdbc); } }
    @Test void stalePreviewCannotReleaseProtectionAndNewProtectionRevokesReclaimability() {
        sessions.save(session("s")); long revision=service.preview().revision();
        service.protect(PROFILE,List.of());
        assertEquals("PREVIEW_CHANGED",assertThrows(StorageService.Failure.class,()->service.reclaimable(List.of("s"),revision)).code);
        service.reclaimable(List.of("s"),service.preview().revision()); service.protect(PROFILE,List.of("s"));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM storage_reclaimable",Integer.class));
        assertEquals("WORKSPACE_PROTECTED",assertThrows(StorageService.Failure.class,()->service.reclaimable(List.of("s"),service.preview().revision())).code);
    }
    @Test void creationAndLostResponseReplayProtectOriginalWithoutChangingReceipt() {
        var first=sessions.createWithOrigin(session("first"),null,"request","fingerprint",PROFILE);
        var replay=sessions.createWithOrigin(session("discarded"),null,"request","fingerprint",PROFILE);
        assertEquals(first.id(),replay.id()); assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM storage_protection",Integer.class));
        sessions.delete(first.id());
        assertEquals("CREATION_TARGET_GONE",assertThrows(SessionStore.CreationFailure.class,()->sessions.createWithOrigin(session("new"),null,"request","fingerprint",PROFILE)).code);
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM session_creation_receipts",Integer.class));
    }
    @Test void deleteMatrixPreservesHistoryReceiptAndOneEndedLink() {
        sessions.createWithOrigin(session("source"),null,"key","fp");
        sessions.createWithOrigin(session("target"),new SessionEditLink.Origin("source","node","source","new-node"));
        jdbc.update("INSERT INTO history (id,session_id,sql_text,executed_at,duration_ms,row_count) VALUES('h','source','SELECT 1','2000-01-01T00:00:00Z',0,0)");
        jdbc.update("INSERT INTO diagnostic_reports (id,session_id,fragment_id,findings_json,created_at) VALUES('r','source','n','[]','2000-01-01T00:00:00Z')");
        jdbc.update("INSERT INTO edit_audit VALUES('a','source','a','b','EDIT',NULL,'2000-01-01T00:00:00Z')");
        sessions.delete("source");
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM history",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM session_creation_receipts",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM session_edit_links",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM diagnostic_reports",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM edit_audit",Integer.class));
        sessions.delete("target"); assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM session_edit_links",Integer.class));
    }
    @Test void timestampCutoffEqualityAndMalformedDatesAreRetained() throws Exception {
        service.protect(PROFILE,List.of());
        for(String stamp:List.of("2026-09-13T00:00:00Z","invalid","2000-01-01"))
            jdbc.update("INSERT INTO history (id,session_id,sql_text,executed_at,duration_ms,row_count) VALUES(?, 's','SELECT 1',?,0,0)",stamp,stamp);
        assertTrue(service.preview().degraded());
        var job=finish(service.start(1,"manual"));
        assertEquals("partial",job.status()); assertTrue(job.skippedReasons().contains("INVALID_RETENTION_TIMESTAMP"));
        assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM history",Integer.class));
    }
    @Test void cleanupCountsIncludeFreshReportsCascadedByExpiredSessionDeletion() throws Exception {
        sessions.save(session("old")); old("old"); service.protect(PROFILE,List.of());
        service.reclaimable(List.of("old"),service.preview().revision());
        jdbc.update("INSERT INTO diagnostic_reports (id,session_id,fragment_id,findings_json,created_at) VALUES('report','old','node','[]','2026-10-13T00:00:00Z')");
        jdbc.update("INSERT INTO edit_audit VALUES('audit','old','a','b','EDIT',NULL,'2026-10-13T00:00:00Z')");
        var job=finish(service.start(1,"manual"));
        assertEquals(1,job.deleted().get("sessions")); assertEquals(1,job.deleted().get("diagnostic_reports")); assertEquals(1,job.deleted().get("edit_audit"));
    }
    @Test void physicalVacuumReclaimsBytesAndHonorsCapacity() throws Exception {
        service.protect(PROFILE,List.of()); service.apply(false,30,16,1);
        String payload="x".repeat(128*1024);
        for(int i=0;i<180;i++) jdbc.update("INSERT INTO history (id,session_id,sql_text,executed_at,duration_ms,row_count) VALUES(?, 's',?,'2000-01-01T00:00:00Z',0,0)","h"+i,payload);
        var measuredBefore=service.measure(); long before=measuredBefore.totalBytes(); assertTrue(before>16*1024*1024);
        var job=finish(service.start(2,"manual"));
        assertEquals("completed",job.status()); assertEquals(180,job.deleted().get("history"));
        assertTrue(job.targetMet()); assertTrue(job.after().totalBytes()<before/2);
        System.out.println("M2_REAL_FILE_RECLAIM before="+measuredBefore+" after="+job.after()+" deleted="+job.deleted());
    }
    @Test void protectedBytesReportUnmetTarget() throws Exception {
        sessions.save(session("large")); jdbc.update("UPDATE sessions SET sql_text=? WHERE id='large'","x".repeat(18*1024*1024));
        service.protect(PROFILE,List.of("large")); service.apply(false,30,16,1);
        var job=finish(service.start(2,"manual"));
        assertFalse(job.targetMet()); assertEquals("PROTECTED_DATA_EXCEEDS_TARGET",job.errorReason());
        assertTrue(sessions.findById("large").isPresent());
    }
    @Test void externalWalReaderDefersCompactionRatherThanClaimingSuccess() throws Exception {
        service.protect(PROFILE,List.of());
        try(var reader=DriverManager.getConnection(ds.getUrl());var s=reader.createStatement()) {
            s.execute("BEGIN"); s.executeQuery("SELECT * FROM storage_policy").close();
            jdbc.update("INSERT INTO history (id,session_id,sql_text,executed_at,duration_ms,row_count) VALUES('h','s',?,'2000-01-01T00:00:00Z',0,0)","x".repeat(1024*1024));
            var job=finish(service.start(1,"manual"));
            assertEquals("partial",job.status()); assertTrue(job.skippedReasons().contains("COMPACTION_DEFERRED"));
            s.execute("ROLLBACK");
        }
    }
    @Test void activeLocalLeaseBlocksVacuumAndHistoryDuplicatesRemainIdempotent() throws Exception {
        try(var connection=ds.getConnection()) { assertThrows(StorageDataSource.Maintenance.class,ds::compact); }
        var history=new HistoryStore(jdbc); var entry=new HistoryEntry("exec","owner","SELECT 1",Instant.now(),0,0);
        history.save(entry); history.save(entry);
        assertEquals(1,history.findRecent(10).size());
        assertEquals("HISTORY_CONFLICT",assertThrows(StorageService.Failure.class,()->history.save(new HistoryEntry("exec","other","SELECT 2",Instant.now(),0,0))).code);
    }
    @Test void disabledSchedulerDoesNotStartAJob() {
        service.protect(PROFILE,List.of()); service.scheduled(); assertNull(service.status().get("job"));
    }
    @Test void enabledSchedulerCannotDeleteBeforeProtectionInventoryIsConfirmed() {
        service.apply(true,1,16,1);
        service.scheduled(); assertNotNull(service.status().get("errorReason")); assertNull(service.status().get("job"));
    }
    @Test void cancellationAfterCommittedBatchesPreservesCountsAndStopsCapacityEviction() throws Exception {
        var entered=new java.util.concurrent.CountDownLatch(1); var release=new java.util.concurrent.CountDownLatch(1);
        var gated=new StorageDataSource() { @Override public void compact() throws Exception {
            entered.countDown(); if(!release.await(10,java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("Test release missing"); super.compact();
        }};
        gated.setUrl(ds.getUrl()); service.close(); service=new StorageService(new JdbcTemplate(gated)); service.protect(PROFILE,List.of());
        for(int i=0;i<410;i++) jdbc.update("INSERT INTO history (id,session_id,sql_text,executed_at,duration_ms,row_count) VALUES(?,'s','SELECT 1','2000-01-01T00:00:00Z',0,0)","old"+i);
        jdbc.update("INSERT INTO history (id,session_id,sql_text,executed_at,duration_ms,row_count) VALUES('fresh','s','SELECT 1','2099-01-01T00:00:00Z',0,0)");
        var initial=service.start(1,"manual"); assertTrue(entered.await(10,java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(initial.id(),service.start(1,"manual").id()); service.cancel(initial.id()); release.countDown();
        var job=finish(initial); assertEquals("partial",job.status()); assertEquals("CLEANUP_STOPPED",job.errorReason());
        assertEquals(410,job.deleted().get("history")); assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM history",Integer.class));
    }
    @Test void concurrentlyRegisteredProtectionWinsOverReclaimableInventory() throws Exception {
        service.protect(PROFILE,List.of()); var executor=java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            for(int i=0;i<15;i++) {
                String id="race"+i; sessions.save(session(id)); long revision=service.preview().revision();
                var protection=executor.submit(()->service.protect(PROFILE,List.of(id)));
                try { service.reclaimable(List.of(id),revision); } catch(StorageService.Failure conflict) { assertTrue(List.of("PREVIEW_CHANGED","WORKSPACE_PROTECTED").contains(conflict.code)); }
                protection.get();
                assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM storage_reclaimable WHERE session_id=?",Integer.class,id));
                assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM storage_protection WHERE session_id=?",Integer.class,id));
            }
        } finally { executor.shutdownNow(); }
    }
}
