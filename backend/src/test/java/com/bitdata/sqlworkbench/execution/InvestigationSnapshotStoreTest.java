package com.bitdata.sqlworkbench.execution;

import com.bitdata.sqlworkbench.config.SqliteConfig;
import com.bitdata.sqlworkbench.diagnostics.Evaluation;
import com.bitdata.sqlworkbench.diagnostics.mapping.PlanSqlMappingService;
import com.bitdata.sqlworkbench.execution.model.*;
import com.bitdata.sqlworkbench.sql.SqlPositionService;
import com.bitdata.sqlworkbench.sql.model.*;
import com.bitdata.sqlworkbench.storage.StorageDataSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class InvestigationSnapshotStoreTest {
    @TempDir Path dir;
    JdbcTemplate jdbc; ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    Clock clock = Clock.fixed(Instant.parse("2026-10-27T10:00:00Z"), ZoneOffset.UTC);
    PlanObservationReceiptStore receipts; InvestigationSnapshotStore snapshots; PlanSqlMappingService mapping;
    @BeforeEach void setup() {
        var ds=new StorageDataSource(); ds.setUrl("jdbc:sqlite:"+dir.resolve("snapshot.db")); jdbc=new JdbcTemplate(ds);
        ReflectionTestUtils.invokeMethod(new SqliteConfig(),"initSchema",jdbc);
        receipts=new PlanObservationReceiptStore(jdbc,mapper,clock);
        mapping=mock(PlanSqlMappingService.class);
        snapshots=new InvestigationSnapshotStore(jdbc,mapper,receipts,mapping,clock);
    }
    ExecutionResult result(String execution, String sql) {
        var capture=new PlanCapture(1,"estimated","estimated",sql,"EXPLAIN "+sql,"c","s","postgresql","PostgreSQL","16.14",clock.instant().toString(),null,"COMPLETE",List.of(),
            new PlanDetails(1,"POSTGRESQL_JSON","raw","db","user",Map.of("z","a","a","b"),null,null,List.of(),null));
        return new ExecutionResult(execution,ExecutionStatus.COMPLETED,null,null,0,null,
            new ExecutionResult.PlanNode("Result",0,1,1,0,0,List.of()),null,null).withCapture(capture);
    }
    InvestigationSnapshotStore.CreateRequest request(String observation, String request, FragmentNode workspace, Evaluation evaluation) {
        return new InvestigationSnapshotStore.CreateRequest(observation,request,workspace==null?null:"s",
            new InvestigationSnapshotStore.Annotation("调查","hypothesis","observation"),workspace,evaluation,List.of(),true);
    }
    String receipt(String execution) {
        var saved=receipts.save(result(execution,"SELECT 1"),"s","c","postgresql");
        assertEquals("SAVED",saved.status()); return saved.receiptId();
    }
    @Test void durablePlanOnlyIsImmutableIdempotentAndIndependentOfSession() {
        String id=receipt("e"); var r=request(id,"req",null,null);
        var created=snapshots.create(r); assertEquals(created,snapshots.create(r));
        var cold=new InvestigationSnapshotStore(jdbc,mapper,new PlanObservationReceiptStore(jdbc,mapper,clock),mapping,clock);
        var restored=cold.get(created.snapshotId());
        assertEquals("SELECT 1",restored.content().source().submittedSql());
        assertEquals("NOT_AVAILABLE",restored.content().workspace().state());
        assertEquals("MAPPING_REQUIRES_WORKSPACE",restored.content().mapping().reason());
        assertTrue(cold.page(null,20).items().get(0).partial());
        assertTrue(receipts.page(null,20).items().isEmpty()); verifyNoInteractions(mapping);
        cold.protect(created.snapshotId(),true); cold.protect(created.snapshotId(),true);
        assertTrue(cold.page(null,20).items().get(0).pinned());
        cold.delete(created.snapshotId()); cold.delete(created.snapshotId());
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM investigation_snapshot_protection",Integer.class));
        assertEquals("SNAPSHOT_TARGET_GONE",assertThrows(InvestigationSnapshotStore.Failure.class,()->cold.create(r)).code);
    }
    @Test void repeatedReceiptAndMismatchedIdentityNeverOverwriteEvidence() {
        String id=receipt("e");
        assertEquals(id,receipts.save(result("e","SELECT 1"),"s","c","postgresql").receiptId());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM plan_observation_receipts",Integer.class));
        assertEquals("FAILED",receipts.save(result("e","SELECT 1"),"wrong","c","postgresql").status());
        var root=new FragmentNode("root",FragmentType.ORIGINAL,"sql","SELECT 2",true,null,0,null,List.of());
        assertEquals("SNAPSHOT_WORKSPACE_SCOPE_MISMATCH",assertThrows(InvestigationSnapshotStore.Failure.class,()->snapshots.create(request(id,"r",root,null))).code);
        var correct=new FragmentNode("root",FragmentType.ORIGINAL,"sql","SELECT 1",true,null,0,null,List.of());
        assertEquals("SNAPSHOT_DIAGNOSTICS_SCOPE_MISMATCH",assertThrows(InvestigationSnapshotStore.Failure.class,()->snapshots.create(request(id,"r",correct,new Evaluation(1,"other","VERIFIED",List.of(),false,null)))).code);
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM investigation_snapshots",Integer.class));
    }
    @Test void requestConflictsExpiryCorruptionAndBudgetAreObservable() {
        String id=receipt("e"); var r=request(id,"r",null,null); var created=snapshots.create(r);
        var different=new InvestigationSnapshotStore.CreateRequest(id,"r",null,new InvestigationSnapshotStore.Annotation("different",null,null),null,null,null,true);
        assertEquals("SNAPSHOT_REQUEST_CONFLICT",assertThrows(InvestigationSnapshotStore.Failure.class,()->snapshots.create(different)).code);
        jdbc.update("UPDATE investigation_snapshots SET snapshot_json=replace(snapshot_json,'hypothesis','changed') WHERE id=?",created.snapshotId());
        assertEquals("SNAPSHOT_CORRUPT",assertThrows(InvestigationSnapshotStore.Failure.class,()->snapshots.get(created.snapshotId())).code);
        String next=receipt("next");
        var expired=new PlanObservationReceiptStore(jdbc,mapper,Clock.offset(clock,Duration.ofHours(24)));
        assertEquals("SNAPSHOT_OBSERVATION_EXPIRED",assertThrows(PlanObservationReceiptStore.Failure.class,()->expired.get(next)).code);
        assertTrue(expired.page(null,20).items().isEmpty());
        assertEquals("SNAPSHOT_BUDGET_EXCEEDED",receipts.save(result("big","SELECT '"+"x".repeat(4*1024*1024)+"'"),"s","c","postgresql").errorReason());
    }
    @Test void failedSqliteWriteDoesNotEscapeTheReceiptBoundary() {
        jdbc.execute("DROP TABLE sessions");
        assertEquals("SNAPSHOT_RECEIPT_PERSISTENCE_FAILED",receipts.save(result("e","SELECT 1"),"s","c","postgresql").errorReason());
    }
    @Test void stablePaginationAndSourceNameSurviveSessionDeletion() {
        jdbc.update("INSERT INTO sessions(id,name,sql_text,dialect,created_at,updated_at) VALUES('s','source','SELECT 1','postgresql',?,?)",clock.instant().toString(),clock.instant().toString());
        receipt("a"); receipt("b"); receipt("c");
        var first=receipts.page(null,2); assertEquals(2,first.items().size()); assertNotNull(first.nextCursor());
        var second=receipts.page(first.nextCursor(),2); assertEquals(1,second.items().size());
        assertFalse(first.items().stream().anyMatch(item->item.id().equals(second.items().get(0).id())));
        jdbc.update("DELETE FROM sessions WHERE id='s'");
        assertEquals("source",receipts.page(null,2).items().get(0).sessionName());
        assertNotNull(snapshots.create(request(first.items().get(0).id(),"cold",null,null)));
    }

    @Test void tamperedJsonIsUnavailableInBothListAndDetailWithoutHidingHealthySiblings() {
        String bad=snapshots.create(request(receipt("bad"),"bad",null,null)).snapshotId();
        String good=snapshots.create(request(receipt("good"),"good",null,null)).snapshotId();
        jdbc.update("UPDATE investigation_snapshots SET snapshot_json=replace(snapshot_json,'hypothesis','tampered') WHERE id=?",bad);
        assertEquals("SNAPSHOT_CORRUPT",assertThrows(InvestigationSnapshotStore.Failure.class,()->snapshots.get(bad)).code);
        var rows=mapper.valueToTree(snapshots.page(null,50)).get("items");
        assertEquals(2,rows.size());
        for(var item:rows) {
            assertEquals(item.get("id").asText().equals(bad)?"UNAVAILABLE":"AVAILABLE",item.path("availability").asText());
            if(item.get("id").asText().equals(bad)) assertEquals("SNAPSHOT_CORRUPT",item.path("errorReason").asText());
        }
        assertNotNull(snapshots.get(good));
    }

    @Test void oneCorruptReceiptDoesNotPoisonPaginationAndRemainsDiscardable() {
        String bad=receipt("bad"); receipt("good");
        jdbc.update("UPDATE plan_observation_receipts SET payload_json='{}' WHERE id=?",bad);
        var page=receipts.page(null,50); assertEquals(2,page.items().size());
        var rows=mapper.valueToTree(page).get("items");
        for(var item:rows) if(item.get("id").asText().equals(bad)) {
            assertEquals("UNAVAILABLE",item.path("availability").asText());
            assertEquals("SNAPSHOT_CORRUPT",item.path("errorReason").asText());
        }
        receipts.discard(bad); assertEquals(1,receipts.page(null,50).items().size());
    }

    @Test void validHashCannotAuthorizeMissingOrConflictingEvidenceStructure() throws Exception {
        for(String field:List.of("source","workspace","diagnostics","mapping")) {
            String id=snapshots.create(request(receipt(field),field,null,null)).snapshotId();
            var tree=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.readTree(jdbc.queryForObject("SELECT snapshot_json FROM investigation_snapshots WHERE id=?",String.class,id));
            ((com.fasterxml.jackson.databind.node.ObjectNode)tree.get("content")).putNull(field);
            String hash=PlanObservationReceiptStore.sha256(mapper.writeValueAsString(tree.get("content")));
            ((com.fasterxml.jackson.databind.node.ObjectNode)tree.get("integrity")).put("payloadSha256",hash);
            String json=mapper.writeValueAsString(tree);
            jdbc.update("UPDATE investigation_snapshots SET snapshot_json=?,payload_sha256=?,byte_size=? WHERE id=?",json,hash,json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length,id);
            assertEquals("SNAPSHOT_CORRUPT",assertThrows(InvestigationSnapshotStore.Failure.class,()->snapshots.get(id)).code,field);
        }
    }

    @Test void legacyDoubleClockExpiryIsCappedWithoutRewritingArchivedPayload() throws Exception {
        String id=receipt("legacy");
        var tree=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.readTree(jdbc.queryForObject("SELECT payload_json FROM plan_observation_receipts WHERE id=?",String.class,id));
        String expires=clock.instant().plus(Duration.ofHours(24)).plusNanos(100000).toString();
        tree.put("expiresAt",expires);
        String json=mapper.writeValueAsString(tree);
        jdbc.update("UPDATE plan_observation_receipts SET payload_json=?,payload_sha256=?,expires_at=?,byte_size=? WHERE id=?",json,PlanObservationReceiptStore.sha256(json),expires,json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length,id);
        assertEquals(expires,receipts.get(id).expiresAt());
        String archived=snapshots.create(request(id,"legacy",null,null)).snapshotId();
        var later=Clock.offset(clock,Duration.ofHours(24));
        var lateReceipts=new PlanObservationReceiptStore(jdbc,mapper,later);
        assertEquals("SNAPSHOT_OBSERVATION_EXPIRED",assertThrows(PlanObservationReceiptStore.Failure.class,()->lateReceipts.get(id)).code);
        var cold=new InvestigationSnapshotStore(jdbc,mapper,lateReceipts,mapping,later);
        assertEquals(expires,cold.get(archived).content().observation().expiresAt());
        assertEquals(json,jdbc.queryForObject("SELECT payload_json FROM plan_observation_receipts WHERE id=?",String.class,id));
    }

    @Test void concurrentIdenticalRequestsSealOneImmutableSnapshot() throws Exception {
        var r=request(receipt("concurrent"),"concurrent",null,null);
        var executor=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var a=executor.submit(()->snapshots.create(r));
            var b=executor.submit(()->snapshots.create(r));
            assertEquals(a.get().snapshotId(),b.get().snapshotId());
            assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM investigation_snapshots",Integer.class));
            assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM investigation_snapshot_requests",Integer.class));
        } finally { executor.shutdownNow(); }
    }

    @Test void finalEnvelopeBudgetCountsAllRepeatedUtf8SqlWithoutPartialWrites() {
        String sql="SELECT '"+"中".repeat(400000)+"'";
        var outcome=receipts.save(result("utf8-budget",sql),"s","c","postgresql");
        assertEquals("SAVED",outcome.status());
        var failure=assertThrows(InvestigationSnapshotStore.Failure.class,()->snapshots.create(request(outcome.receiptId(),"utf8-budget",null,null)));
        assertEquals("SNAPSHOT_BUDGET_EXCEEDED",failure.code);
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM investigation_snapshots",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM investigation_snapshot_requests",Integer.class));
        assertEquals(1,receipts.page(null,20).items().size());
    }
}
