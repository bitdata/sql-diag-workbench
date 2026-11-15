package com.bitdata.sqlworkbench.session;

import com.bitdata.sqlworkbench.config.SqliteConfig;
import com.bitdata.sqlworkbench.sql.diff.StructuralSqlCodec;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

class WorkspaceIdentityTest {
    @TempDir Path dir;
    JdbcTemplate jdbc;
    SessionStore store;
    WorkspaceIdentity guard;
    String url;

    @BeforeEach void setup() {
        url = "jdbc:sqlite:" + dir.resolve("workspace.db") + "?busy_timeout=5000";
        jdbc = new JdbcTemplate(new DriverManagerDataSource(url));
        ReflectionTestUtils.invokeMethod(new SqliteConfig(), "initSchema", jdbc);
        store = new SessionStore(jdbc);
        guard = new WorkspaceIdentity(store);
        store.save(Session.builder().id("current").identityPolicy(StructuralSqlCodec.POLICY).build());
        store.save(Session.builder().id("legacy").workspaceSnapshotJson("old bytes").fragmentTreeJson("old tree").build());
    }

    IdentityContext context(String id, long revision) { return new IdentityContext(id,StructuralSqlCodec.POLICY,revision); }
    void denied(String code, Runnable operation) {
        assertEquals(code,assertThrows(WorkspaceIdentity.Failure.class,operation::run).code);
    }

    @Test void migrationIsAdditiveAndLegacyCannotBeAdmitted() {
        ReflectionTestUtils.invokeMethod(new SqliteConfig(), "initSchema", jdbc);
        var legacy = store.findById("legacy").orElseThrow();
        assertEquals(IdentityContext.LEGACY_POLICY,legacy.identityPolicy());
        assertEquals("old bytes",legacy.workspaceSnapshotJson());
        assertEquals("old tree",legacy.fragmentTreeJson());
        denied("IDENTITY_POLICY_READ_ONLY", () -> guard.require(context("legacy",0),"postgresql"));
        denied("IDENTITY_CONTEXT_REQUIRED", () -> guard.require(null,"postgresql"));
        denied("WORKSPACE_NOT_FOUND", () -> guard.require(context("gone",0),"postgresql"));
        denied("WORKSPACE_DIALECT_MISMATCH", () -> guard.require(context("current",0),"mysql"));
        denied("IDENTITY_POLICY_MISMATCH", () -> guard.require(new IdentityContext("current","wrong",0L),"postgresql"));
    }

    @Test void jacksonRejectsCoercionNegativeAndOverflowRevisions() throws Exception {
        var json = new ObjectMapper();
        String prefix = "{\"sessionId\":\"s\",\"identityPolicy\":\"p\",\"workspaceRevision\":";
        for (String value : List.of("-1","1.1","\"0\"","true","9223372036854775808")) {
            assertThrows(Exception.class, () -> json.readValue(prefix + value + "}",IdentityContext.class),value);
        }
        assertEquals(0L,json.readValue(prefix + "0}",IdentityContext.class).workspaceRevision());
    }

    @Test void unsavedNodeProofsAreRecomputedAndSourceSqlMustMatch() {
        var form = StructuralSqlCodec.canonicalize("SELECT 1","postgresql");
        var proof = new NodeProof(form.id(),"SELECT 1",form.canonical());
        var nodes = guard.verifyNodes(List.of(proof),"postgresql");
        guard.requireSource(form.id(),"SELECT 1","postgresql",nodes);
        denied("DUPLICATE_NODE_PROOF", () -> guard.verifyNodes(List.of(proof,proof),"postgresql"));
        denied("NODE_IDENTITY_MISMATCH", () -> guard.verify(new NodeProof(form.id(),"SELECT 2",form.canonical()),"postgresql"));
        denied("SOURCE_SQL_IDENTITY_MISMATCH", () -> guard.requireSource(form.id(),"SELECT 2","postgresql",nodes));
        denied("SOURCE_NODE_PROOF_REQUIRED", () -> guard.requireSource("unknown","SELECT 1","postgresql",nodes));
        denied("CANONICAL_UNAVAILABLE", () -> guard.verify(new NodeProof("fake","SELECT ?","fake"),"postgresql"));
    }

    @Test void revisionCasPreventsStaleWritersAndDeletionNeverResurrects() {
        var updated = store.replaceWorkspace(context("current",0),"SELECT 2","postgresql",null,null,null);
        assertEquals(1,updated.workspaceRevision());
        denied("WORKSPACE_REVISION_CONFLICT", () -> store.replaceWorkspace(context("current",0),"late","postgresql",null,null,null));
        denied("DIALECT_SWITCH_REQUIRES_EMPTY_GRAPH", () -> store.replaceWorkspace(context("current",1),"SELECT 1","mysql",null,"graph","tree"));
        updated = store.replaceWorkspace(context("current",1),"SELECT 3","mysql",null,null,null);
        assertEquals(2,updated.workspaceRevision());
        assertNull(updated.workspaceSnapshotJson());
        store.delete("current");
        denied("WORKSPACE_NOT_FOUND", () -> store.replaceWorkspace(context("current",2),"late","mysql",null,null,null));
        assertTrue(store.findById("current").isEmpty());
    }

    @Test void dialectSwitchAcceptsTextOnlySnapshotButRejectsGraphsAndMalformedSnapshots() throws Exception {
        var json = new ObjectMapper();
        var empty = json.createObjectNode().put("version",3).put("identityPolicy",StructuralSqlCodec.POLICY)
            .put("dialect","sqlserver").put("parsedSql","");
        empty.putNull("graph"); empty.putNull("treeProjection"); empty.putNull("selectedNodeId");
        for (String field : List.of("graph","treeProjection","selectedNodeId")) {
            var invalid = empty.deepCopy(); invalid.putObject(field);
            denied("DIALECT_SWITCH_REQUIRES_EMPTY_GRAPH", () -> store.replaceWorkspace(context("current",0),"SELECT 1","sqlserver",null,invalid.toString(),null));
        }
        denied("DIALECT_SWITCH_REQUIRES_EMPTY_GRAPH", () -> store.replaceWorkspace(context("current",0),"SELECT 1","sqlserver",null,"{}",null));
        denied("DIALECT_SWITCH_REQUIRES_EMPTY_GRAPH", () -> store.replaceWorkspace(context("current",0),"SELECT 1","sqlserver",null,"invalid json",null));
        var updated = store.replaceWorkspace(context("current",0),"SELECT 1","sqlserver",null,empty.toString(),null);
        assertEquals("sqlserver",updated.dialect());
        assertEquals(empty.toString(),updated.workspaceSnapshotJson());
    }

    @Test void twoIndependentConnectionsHaveOneWinner() throws Exception {
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        Callable<String> save = () -> {
            var other = new SessionStore(new JdbcTemplate(new DriverManagerDataSource(url)));
            start.await();
            try { other.replaceWorkspace(context("current",0),"SELECT 1","postgresql",null,null,null); return "200"; }
            catch (WorkspaceIdentity.Failure failure) { return failure.code; }
        };
        try {
            var first = executor.submit(save); var second = executor.submit(save); start.countDown();
            var results = List.of(first.get(10,TimeUnit.SECONDS),second.get(10,TimeUnit.SECONDS));
            assertTrue(results.contains("200"),results.toString());
            assertTrue(results.contains("WORKSPACE_REVISION_CONFLICT"),results.toString());
            assertEquals(1,store.findById("current").orElseThrow().workspaceRevision());
        } finally { executor.shutdownNow(); }
    }
}
