package com.bitdata.sqlworkbench.diagnostics.mapping;

import com.bitdata.sqlworkbench.connection.DatabaseStrategyFactory;
import com.bitdata.sqlworkbench.execution.model.ExecutionResult;
import com.bitdata.sqlworkbench.execution.model.NodeMetrics;
import com.bitdata.sqlworkbench.execution.model.PlanCapture;
import com.bitdata.sqlworkbench.execution.model.PlanDetails;
import com.bitdata.sqlworkbench.sql.SqlDialectResolver;
import com.bitdata.sqlworkbench.sql.SqlPositionService;
import com.bitdata.sqlworkbench.sql.model.FragmentNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PlanSqlMappingServiceTest {
    private final PlanSqlMappingService service = new PlanSqlMappingService(
        new SqlPositionService(new SqlDialectResolver(DatabaseStrategyFactory.withBuiltinStrategies())));

    @Test
    void uniqueTableScanMapsToSqlSpanAndFragment() {
        String sql = "SELECT id FROM sqldiag_it_customers WHERE region = 'us-east'";
        var plan = node("$[0].Plan", "Seq Scan", "sqldiag_it_customers", null);
        var result = result(sql, plan, "$[0].Plan");
        var root = new FragmentNode("root", com.bitdata.sqlworkbench.sql.model.FragmentType.ORIGINAL,
            "Original", sql, true, null, 0, null, List.of());

        PlanSqlMapping mapping = service.map("exec-1", "postgresql", sql, result, root, List.of());

        assertFalse(mapping.degraded());
        assertTrue(mapping.edges().stream().anyMatch(e -> e.relation() == PlanSqlMapping.Relation.PLAN_TO_SQL
            && e.confidence() == PlanSqlMapping.Confidence.EXACT
            && e.span() != null));
        assertTrue(mapping.edges().stream().anyMatch(e -> e.relation() == PlanSqlMapping.Relation.PLAN_TO_FRAGMENT
            && "root".equals(e.targetId())));
        assertEquals(64, mapping.submittedSqlSha256().length());
    }

    @Test
    void missingPlanPathIsObservableUnknown() {
        String sql = "SELECT id FROM t";
        var plan = new ExecutionResult.PlanNode("Seq Scan", 0, 1, 1, 1, 1, List.of(), "t",
            ExecutionResult.PlanNodeConditions.EMPTY, null);
        PlanSqlMapping mapping = service.map("exec-1", "postgresql", sql, result(sql, plan, null), null, List.of());

        assertTrue(mapping.degraded());
        assertTrue(mapping.issues().stream().anyMatch(i -> i.code().equals("PLAN_NODE_PATH_UNAVAILABLE")));
        assertTrue(mapping.edges().stream().anyMatch(e -> e.confidence() == PlanSqlMapping.Confidence.UNKNOWN));
    }

    @Test
    void unavailablePlanDoesNotPretendThereAreNoMappings() {
        String sql = "SELECT 1";
        PlanSqlMapping mapping = service.map("exec-3", "mysql", sql, null, null, List.of());

        assertTrue(mapping.degraded());
        assertEquals("PLAN_SOURCE_UNAVAILABLE", mapping.errorReason());
        assertTrue(mapping.issues().stream().anyMatch(i -> i.code().equals("PLAN_SOURCE_UNAVAILABLE")));
    }

    @Test
    void executionSqlAndDialectMismatchRejectAllEdges() {
        String sql = "SELECT id FROM t";
        var result = result(sql, node("$[0].Plan", "Seq Scan", "t", null), "$[0].Plan");
        for (var mapping : List.of(service.map("other", "postgresql", sql, result, null, List.of()),
                service.map("exec-1", "mysql", sql, result, null, List.of()),
                service.map("exec-1", "postgresql", "SELECT * FROM t", result, null, List.of()))) {
            assertTrue(mapping.degraded());
            assertEquals("MAPPING_SCOPE_MISMATCH", mapping.errorReason());
            assertTrue(mapping.edges().isEmpty());
        }
    }

    @Test
    void tableSubstringsInColumnsAndLiteralsNeverCreateRelationEdges() {
        String sql = "SELECT orders_id, 'orders' FROM preorders";
        var mapping = service.map("exec-1", "postgresql", sql,
            result(sql, node("$[0].Plan", "Seq Scan", "orders", null), "$[0].Plan"), null, List.of());
        assertTrue(mapping.edges().stream().filter(e -> e.span() != null).allMatch(e -> "QUERY".equals(e.span().type())));
    }

    @Test
    void selfJoinAndUnionKeepEveryOccurrenceAndAliasIsOnlyInferred() {
        for (String sql : List.of("SELECT a.id FROM orders a JOIN orders b ON a.id=b.id",
                "SELECT id FROM orders UNION ALL SELECT id FROM orders")) {
            var mapping = service.map("exec-1", "postgresql", sql,
                result(sql, node("$[0].Plan", "Seq Scan", "orders", null), "$[0].Plan"), null, List.of());
            var spans = mapping.edges().stream().filter(e -> e.span() != null && e.span().type().equals("TABLE_REF")).toList();
            assertEquals(2, spans.size());
            assertNotEquals(spans.get(0).span().startOffset(), spans.get(1).span().startOffset());
            assertTrue(spans.stream().allMatch(e -> e.confidence() == PlanSqlMapping.Confidence.INFERRED));
        }
        String sql = "SELECT o.id FROM orders o";
        var mapping = service.map("exec-1", "postgresql", sql,
            result(sql, node("$[0].Plan", "Seq Scan", "o", null), "$[0].Plan"), null, List.of());
        assertTrue(mapping.edges().stream().anyMatch(e -> e.span() != null && e.span().label().equals("orders")
            && e.confidence() == PlanSqlMapping.Confidence.INFERRED));
    }

    @Test
    void rawEvidenceAndFragmentScopeFailuresAreVisible() {
        String sql = "SELECT id FROM orders";
        var mapping = service.map("exec-1", "postgresql", sql,
            result(sql, node("$[0].Plan", "Seq Scan", "orders", null), null),
            new FragmentNode("foreign", com.bitdata.sqlworkbench.sql.model.FragmentType.ORIGINAL,
                "foreign", "SELECT id FROM other_table", true, null, 0, null, List.of()), List.of());
        assertTrue(mapping.degraded());
        assertTrue(mapping.issues().stream().anyMatch(i -> i.code().equals("FRAGMENT_SCOPE_MISMATCH")));
        assertTrue(mapping.issues().stream().anyMatch(i -> i.code().equals("PLAN_NODE_EVIDENCE_UNAVAILABLE")));
        assertTrue(mapping.edges().stream().allMatch(e -> e.confidence() == PlanSqlMapping.Confidence.UNKNOWN));
        assertFalse(mapping.edges().stream().anyMatch(e -> "foreign".equals(e.targetId())));
    }

    @Test
    void formattedRootCanBeAlignedButDegradedFragmentIdentityCannotBeInferred() {
        String sql = "SELECT id FROM orders";
        var degraded = new FragmentNode("degraded", com.bitdata.sqlworkbench.sql.model.FragmentType.STRIPPED,
            "Degraded", sql, true, null, 0, null, List.of(), List.of(), null,
            com.bitdata.sqlworkbench.sql.model.SourceKind.AUTO, null, null, null, null, "UNRESOLVED_DEPENDENCY");
        var root = new FragmentNode("root", com.bitdata.sqlworkbench.sql.model.FragmentType.ORIGINAL,
            "Original", "select id /* scope formatting */\nfrom orders", true, null, 1, null, List.of(degraded));
        var mapping = service.map("exec-1", "postgresql", sql,
            result(sql, node("$[0].Plan", "Seq Scan", "orders", null), "$[0].Plan"), root, List.of());
        assertTrue(mapping.edges().stream().anyMatch(e -> "root".equals(e.targetId()) && e.confidence() == PlanSqlMapping.Confidence.INFERRED));
        assertTrue(mapping.edges().stream().anyMatch(e -> "degraded".equals(e.targetId()) && e.confidence() == PlanSqlMapping.Confidence.UNKNOWN));
        assertTrue(mapping.degraded());
        assertEquals("FRAGMENT_IDENTITY_DEGRADED", mapping.errorReason());
    }

    @Test
    void fragmentScopePreservesIdentifierQuotingCaseAndLiteralValues() {
        for (var pair : List.of(
                List.of("SELECT id FROM orders WHERE \"Flag\" = 1", "SELECT id FROM orders WHERE \"flag\" = 1"),
                List.of("SELECT id FROM orders WHERE FLAG = 1", "SELECT id FROM orders WHERE \"FLAG\" = 1"),
                List.of("SELECT id FROM orders WHERE flag = 1", "SELECT id FROM orders WHERE FLAG = 1"),
                List.of("SELECT \"Fn\"(id) FROM orders", "SELECT \"fn\"(id) FROM orders"),
                List.of("SELECT Fn(id) FROM orders", "SELECT \"Fn\"(id) FROM orders"),
                List.of("SELECT CAST(id AS FLAG) FROM orders", "SELECT CAST(id AS \"FLAG\") FROM orders"),
                List.of("SELECT id FROM orders WHERE status = 'A'", "SELECT id FROM orders WHERE status = 'a'"),
                List.of("SELECT id FROM orders WHERE flag = 1", "SELECT id FROM orders WHERE flag = 2"),
                List.of("SELECT id FROM orders WHERE flag > 1", "SELECT id FROM orders WHERE flag < 1"))) {
            String sql = pair.get(0);
            var foreign = new FragmentNode("foreign", com.bitdata.sqlworkbench.sql.model.FragmentType.ORIGINAL,
                "Foreign", pair.get(1), true, null, 0, null, List.of());
            var mapping = service.map("exec-1", "postgresql", sql,
                result(sql, node("$[0].Plan", "Seq Scan", "orders", null), "$[0].Plan"), foreign, List.of());
            assertTrue(mapping.issues().stream().anyMatch(i -> "FRAGMENT_SCOPE_MISMATCH".equals(i.code())), pair.toString());
            assertFalse(mapping.edges().stream().anyMatch(e -> "foreign".equals(e.targetId())), pair.toString());
        }
    }

    @Test
    void formattedBuiltinCastRetainsScopeButNonexactUserTypeIsConservativelyRejected() {
        String sql = "SELECT CAST(id AS INTEGER) FROM orders";
        var formatted = new FragmentNode("formatted", com.bitdata.sqlworkbench.sql.model.FragmentType.ORIGINAL,
            "Formatted", "select CAST(id AS INTEGER) /* formatting */\nfrom orders", true, null, 0, null, List.of());
        var mapping = service.map("exec-1", "postgresql", sql,
            result(sql, node("$[0].Plan", "Seq Scan", "orders", null), "$[0].Plan"), formatted, List.of());
        assertTrue(mapping.edges().stream().anyMatch(e -> "formatted".equals(e.targetId())));
        sql = "SELECT CAST(id AS custom_type) FROM orders";
        var nonexact = new FragmentNode("nonexact", com.bitdata.sqlworkbench.sql.model.FragmentType.ORIGINAL,
            "Nonexact", sql + " ", true, null, 0, null, List.of());
        mapping = service.map("exec-1", "postgresql", sql,
            result(sql, node("$[0].Plan", "Seq Scan", "orders", null), "$[0].Plan"), nonexact, List.of());
        assertTrue(mapping.issues().stream().anyMatch(i -> "FRAGMENT_SCOPE_MISMATCH".equals(i.code())));
    }

    @Test
    void invalidSubmittedSqlProducesStructuredFailureAndFindingsAreOnlyCandidates() {
        String sql = "SELECT ( FROM";
        var invalid = service.map("exec-1", "postgresql", sql,
            result(sql, node("$[0].Plan", "Seq Scan", "orders", null), "$[0].Plan"), null, List.of());
        assertEquals("SQL_POSITION_PARSE_FAILED", invalid.errorReason());
        assertTrue(invalid.degraded());
        sql = "SELECT id FROM orders";
        var finding = com.bitdata.sqlworkbench.diagnostics.DiagnosticFinding.builder().id("client-finding").planNodePath("$[0].Plan").build();
        var mapping = service.map("exec-1", "postgresql", sql,
            result(sql, node("$[0].Plan", "Seq Scan", "orders", null), "$[0].Plan"), null, List.of(finding));
        assertTrue(mapping.edges().stream().anyMatch(e -> "client-finding".equals(e.targetId()) && e.confidence() == PlanSqlMapping.Confidence.INFERRED));
    }

    private static ExecutionResult.PlanNode node(String path, String operation, String table, String filter) {
        return new ExecutionResult.PlanNode(operation, 0, 1, 1, 1, 1, List.of(), table,
            new ExecutionResult.PlanNodeConditions(filter, null, null, null),
            new NodeMetrics(1, "DATABASE_NODE", path, null, null, null, null, null, null));
    }

    private static ExecutionResult result(String sql, ExecutionResult.PlanNode plan, String path) {
        var details = new PlanDetails(1, "POSTGRESQL_JSON", "{}", "db", "user", Map.of(), null, null,
            path == null ? List.of() : List.of(new PlanDetails.NodeEvidence(path, "{\"Relation Name\":\"t\"}", null)), null);
        var capture = new PlanCapture(1, "actual", "actual", sql, "EXPLAIN", "c", "s", "postgresql",
            "PostgreSQL", "16", "now", null, "COMPLETE", List.of(), details);
        return new ExecutionResult("exec-1", com.bitdata.sqlworkbench.execution.model.ExecutionStatus.COMPLETED,
            null, null, 0, null, plan, null, null, null, null, false, null, null, capture);
    }
}
