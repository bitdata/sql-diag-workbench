package com.bitdata.sqlworkbench.integration;

import com.bitdata.sqlworkbench.connection.*;
import com.bitdata.sqlworkbench.diagnostics.DiagnosticsController;
import com.bitdata.sqlworkbench.diagnostics.mapping.PlanSqlMapping;
import com.bitdata.sqlworkbench.execution.ExecutionEngine;
import com.bitdata.sqlworkbench.execution.model.ExecutionStatus;
import com.bitdata.sqlworkbench.integration.support.IntegrationFixtureSupport;
import com.bitdata.sqlworkbench.sql.model.FragmentNode;
import com.bitdata.sqlworkbench.sql.model.FragmentType;
import com.bitdata.sqlworkbench.testsupport.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "sql-workbench.data-dir=${java.io.tmpdir}/sqldiag-it-perf05")
@AutoConfigureMockMvc
@Tag("integration")
class PlanSqlMappingIntegrationTest {
    @Autowired MockMvc http;
    @Autowired ObjectMapper json;
    @Autowired ConnectionService connections;
    @Autowired ExecutionEngine engine;
    @MockBean KeychainStore keychain;

    @Test @RequiresTestDatabase(Dialect.POSTGRESQL)
    void postgresJsonHttpMappingMatrix() throws Exception { run(Dialect.POSTGRESQL); }

    @Test @RequiresTestDatabase(Dialect.MYSQL)
    void mysqlTreeAndJsonHttpMappingMatrix() throws Exception { run(Dialect.MYSQL); }

    private void run(Dialect database) throws Exception {
        var fixture = IntegrationFixtureSupport.connect(database);
        IntegrationFixtureSupport.applyFixture(database, fixture);
        var entry = fixture.entry();
        when(keychain.retrieve(anyString())).thenReturn(entry.password());
        String dialect = database.name().toLowerCase(Locale.ROOT);
        var connection = connections.create(new ConnectionRequest("perf05-" + UUID.randomUUID(), entry.host(), entry.port(),
            entry.database(), entry.username(), entry.password(), dialect, entry.connectionType(), entry.trustServerCertificate()));
        List<String> corpus = List.of(
            "SELECT id, name FROM sqldiag_it_customers WHERE region = 'us-east'",
            "SELECT o.id, c.name FROM sqldiag_it_orders o JOIN sqldiag_it_customers c ON o.customer_id=c.id WHERE o.status='paid'",
            "WITH paid AS (SELECT customer_id, amount FROM sqldiag_it_orders WHERE status='paid') SELECT c.name,p.amount FROM paid p JOIN sqldiag_it_customers c ON p.customer_id=c.id",
            "SELECT x.id FROM (SELECT id,customer_id FROM sqldiag_it_orders WHERE amount>10) x WHERE x.id<20",
            "SELECT c.id FROM sqldiag_it_customers c WHERE EXISTS (SELECT 1 FROM sqldiag_it_orders o WHERE o.customer_id=c.id)",
            "SELECT id FROM sqldiag_it_customers WHERE id<3 UNION ALL SELECT id FROM sqldiag_it_customers WHERE id>3");
        try {
            for (String mode : List.of("actual", "estimated")) for (String sql : corpus) {
                String session = "perf05-" + UUID.randomUUID();
                String execution = engine.analyze(sql, connection.id(), 10, 100, mode, session);
                var result = IntegrationFixtureSupport.pollExecutionTerminal(engine, execution, dialect + " " + mode);
                assertEquals(ExecutionStatus.COMPLETED, result.status(), result.error());
                assertNotNull(result.plan());
                assertEquals(database == Dialect.POSTGRESQL ? "POSTGRESQL_JSON" : mode.equals("actual") ? "MYSQL_TREE" : "MYSQL_JSON", result.planCapture().details().channel());
                // Exact submitted root here: separate frontend tests exercise formatted/stale trees.
                var root = new FragmentNode("root", FragmentType.ORIGINAL, "Submitted", sql, true, null, 0, null, List.of());
                var request = new DiagnosticsController.MappingRequest(execution, sql, dialect, connection.id(), session, root, List.of());
                var mapping = mapping(request);
                // MySQL JSON has application-synthesized wrappers without raw
                // node evidence. Their unknown edges must be observable.
                assertTrue(mapping.issues().stream().allMatch(i -> i.code().equals("PLAN_NODE_EVIDENCE_UNAVAILABLE")), mapping.issues().toString());
                assertEquals(!mapping.issues().isEmpty(), mapping.degraded());
                assertEquals(execution, mapping.executionId());
                String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(sql.getBytes(StandardCharsets.UTF_8)));
                assertEquals(hash, mapping.submittedSqlSha256());
                assertEquals(execution + ":" + dialect + ":" + hash, mapping.captureKey());
                var paths = new ArrayList<String>();
                var planNodes = new ArrayList<com.bitdata.sqlworkbench.execution.model.ExecutionResult.PlanNode>();
                var todo = new ArrayDeque<com.bitdata.sqlworkbench.execution.model.ExecutionResult.PlanNode>();
                todo.add(result.plan());
                while (!todo.isEmpty()) { var node = todo.remove(); planNodes.add(node); paths.add(node.metrics().sourcePath()); todo.addAll(node.children()); }
                assertFalse(mapping.edges().isEmpty());
                boolean treeWithoutRelationMetadata = "MYSQL_TREE".equals(result.planCapture().details().channel());
                if (treeWithoutRelationMetadata) {
                    // The existing TREE adapter intentionally has no structured
                    // tableName. Do not invent relation identity from operation text.
                    assertTrue(planNodes.stream().allMatch(n -> n.tableName() == null));
                    assertFalse(mapping.edges().stream().anyMatch(e -> e.span() != null && "TABLE_REF".equals(e.span().type())));
                    assertTrue(mapping.edges().stream().filter(e -> e.targetKind() == PlanSqlMapping.TargetKind.FRAGMENT)
                        .allMatch(e -> e.targetId() == null && e.confidence() == PlanSqlMapping.Confidence.UNKNOWN));
                    for (String path : paths) assertTrue(mapping.edges().stream().anyMatch(e ->
                        path.equals(e.planNodePath()) && e.targetKind() == PlanSqlMapping.TargetKind.SQL_SPAN
                            && e.span() == null && e.confidence() == PlanSqlMapping.Confidence.UNKNOWN));
                }
                System.out.println("PERF05 mapping metadata " + dialect + "/" + mode + " channel="
                    + result.planCapture().details().channel() + " tables=" + planNodes.stream().map(n -> n.tableName()).toList()
                    + " relationSpans=" + mapping.edges().stream().filter(e -> e.span() != null && "TABLE_REF".equals(e.span().type())).count());
                // Root/UNKNOWN edges alone must not satisfy the relation-mapping matrix.
                // Synthetic wrapper nodes may remain UNKNOWN, while database scans
                // must retain the relation occurrences from this submitted query.
                for (String table : List.of("sqldiag_it_customers", "sqldiag_it_orders")) {
                    if (treeWithoutRelationMetadata) continue;
                    if (!sql.contains(table)) continue;
                    var relationEdges = mapping.edges().stream()
                        .filter(e -> e.span() != null && "TABLE_REF".equals(e.span().type()) && table.equals(e.span().label()))
                        .toList();
                    assertFalse(relationEdges.isEmpty(), dialect + " " + mode + " missing relation: " + table + " in " + sql);
                    long occurrences = relationEdges.stream().map(e -> e.span().startOffset()).distinct().count();
                    long expectedOccurrences = (sql.length() - sql.replace(table, "").length()) / table.length();
                    assertEquals(expectedOccurrences, occurrences, "relation occurrences must keep distinct offsets: " + table);
                    for (var relation : relationEdges) {
                        assertTrue(mapping.edges().stream().anyMatch(e ->
                            Objects.equals(e.planNodePath(), relation.planNodePath())
                                && e.targetKind() == PlanSqlMapping.TargetKind.FRAGMENT && "root".equals(e.targetId())),
                            "scan candidate must also retain its submitted root fragment: " + relation.planNodePath());
                    }
                }
                for (var edge : mapping.edges()) {
                    assertTrue(paths.contains(edge.planNodePath()));
                    if (edge.span() != null) {
                        assertTrue(edge.span().startOffset() >= 0 && edge.span().endOffset() <= sql.length());
                        if (edge.span().type().equals("TABLE_REF")) {
                            assertEquals(edge.span().label(), sql.substring(edge.span().startOffset(), edge.span().endOffset()));
                            assertEquals(PlanSqlMapping.Confidence.INFERRED, edge.confidence());
                        }
                    }
                }
                var mismatch = mapping(new DiagnosticsController.MappingRequest(execution, sql, dialect, connection.id(), "wrong-session", root, List.of()));
                assertTrue(mismatch.degraded());
                assertEquals("PLAN_SOURCE_MISMATCH", mismatch.errorReason());
                assertTrue(mismatch.edges().isEmpty());
            }
        } finally {
            connections.delete(connection.id());
            IntegrationFixtureSupport.dropFixture(database, fixture);
        }
    }

    private PlanSqlMapping mapping(DiagnosticsController.MappingRequest request) throws Exception {
        String body = http.perform(post("/api/diagnostics/plan-mapping").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(request))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readValue(body, PlanSqlMapping.class);
    }
}
