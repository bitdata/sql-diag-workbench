package com.bitdata.sqlworkbench.diagnostics.rules;

import com.bitdata.sqlworkbench.diagnostics.DiagnosticFinding;
import com.bitdata.sqlworkbench.diagnostics.DiagnosticRule;
import com.bitdata.sqlworkbench.execution.MySqlPlanAdapter;
import com.bitdata.sqlworkbench.execution.PostgreSqlPlanAdapter;
import com.bitdata.sqlworkbench.execution.model.ExecutionResult.PlanNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.bitdata.sqlworkbench.diagnostics.rules.DiagnosticRuleTestFixture.*;
import static org.junit.jupiter.api.Assertions.*;

class SequentialScanRuleTest {

    private final SequentialScanRule rule = new SequentialScanRule();

    @Test
    void extractsTableName_fromSeqScanOperation() {
        // PostgreSQL plan: "Seq Scan on orders" — should extract "orders"
        var plan = planNode("Seq Scan on orders", 1000, 150_000);
        var fragment = fragment("f1", "orders WHERE status = 'active'");

        List<DiagnosticFinding> findings = rule.analyze(fragment, plan, timing(200));

        assertEquals(1, findings.size());
        assertTrue(findings.get(0).title().contains("orders"),
            "Title should contain table name 'orders', got: " + findings.get(0).title());
        // Should NOT contain the fragment label
        assertFalse(findings.get(0).title().contains("WHERE"),
            "Title should not contain fragment SQL text");
    }

    @Test
    void extractsTableName_fromSchemaQualified() {
        // "Seq Scan on public.customers"
        var plan = planNode("Seq Scan on public.customers", 1000, 200_000);
        var fragment = fragment("f1", "customers");

        List<DiagnosticFinding> findings = rule.analyze(fragment, plan, timing(200));

        assertEquals(1, findings.size());
        assertTrue(findings.get(0).title().contains("customers"));
    }

    @Test
    void extractsTableName_fromChildSeqScan() {
        // Parent is not a Seq Scan but has a Seq Scan child
        var child = planNode("Seq Scan on line_items", 500, 50_000);
        var parent = planNode("Hash Join", 500, 50_000, List.of(child));
        var fragment = fragment("f1", "line_items JOIN orders");

        List<DiagnosticFinding> findings = rule.analyze(fragment, parent, timing(100));

        // Should still detect the seq scan on the child and extract the table name
        assertEquals(1, findings.size());
        assertTrue(findings.get(0).title().contains("line_items"),
            "Title should contain 'line_items' from child Seq Scan, got: " + findings.get(0).title());
    }

    @Test
    void outputCountDoesNotProveLargeTableOrIndexBenefit() {
        var plan = planNode("Seq Scan on big_table", 1000, 150_000);
        var fragment = fragment("f1", "big_table");

        List<DiagnosticFinding> findings = rule.analyze(fragment, plan, timing(200));

        assertEquals(1, findings.size());
        assertEquals(DiagnosticRule.Severity.LOW, findings.get(0).severity());
    }

    @Test
    void smallTable_getsLowSeverity() {
        var plan = planNode("Seq Scan on small_table", 100, 500);
        var fragment = fragment("f1", "small_table");

        List<DiagnosticFinding> findings = rule.analyze(fragment, plan, timing(10));

        assertEquals(1, findings.size());
        assertEquals(DiagnosticRule.Severity.LOW, findings.get(0).severity());
    }

    @Test
    void noFinding_whenNotSeqScan() {
        var plan = planNode("Index Scan on orders", 1000, 150_000);
        var fragment = fragment("f1", "orders");

        List<DiagnosticFinding> findings = rule.analyze(fragment, plan, timing(200));

        assertTrue(findings.isEmpty());
    }

    @Test
    void noFinding_whenPlanIsNull() {
        var fragment = fragment("f1", "orders");

        List<DiagnosticFinding> findings = rule.analyze(fragment, null, timing(100));

        assertTrue(findings.isEmpty());
    }

    // ── JSON-channel table name (2026-09-09 credential-tier E2E landmine D) ──
    // Real PG/MySQL EXPLAIN JSON carries the relation in a STRUCTURED field and
    // the adapters map operation to the bare node type ("Seq Scan") — the
    // "Seq Scan on <table>" text regex above only exists on the psql-text channel
    // and can NEVER match here. Before the fix the title fell back to
    // fragment.label() ("Original Query" in e2e flow 4).
    //
    // PG key name pinned by a LIVE probe on the seeded DB (2026-09-09):
    // EXPLAIN (ANALYZE, FORMAT JSON) SELECT * FROM sqldiag_it_orders WHERE
    // amount > 100 → root node keys are
    //   Node Type, Parallel Aware, Relation Name, Alias, Startup Cost,
    //   Total Cost, Plan Rows, Plan Width, Actual Startup Time, Actual Total
    //   Time, Actual Rows, Actual Loops, Filter, Rows Removed by Filter
    // The scan target is "Relation Name"; "Table Name" does NOT exist in PG
    // output (task-e-report §3.4 assumed it — corrected by recon, see
    // task-cd-report.md).

    @Test
    void structuredTableName_preferredOverTextForm_andStripsSchemaPrefix() {
        // Both channels present but disagreeing → structured must win; and a
        // (defensive) schema-qualified structured name is stripped to the relation.
        var plan = planNodeWithTable("Seq Scan on legacy_name", 1000, 200_000, "sales.customers");
        var fragment = fragment("f1", "Original Query");

        List<DiagnosticFinding> findings = rule.analyze(fragment, plan, timing(200));

        assertEquals(1, findings.size());
        assertTrue(findings.get(0).title().contains("customers"),
            "Structured table name should win over the text suffix, got: "
                + findings.get(0).title());
        assertFalse(findings.get(0).title().contains("legacy_name"),
            "Text suffix must lose to the structured field, got: " + findings.get(0).title());
    }

    @Test
    void indexScanStructuredTableName_neverLeaksIntoSeqScanTitle() {
        // Hash Join over an Index Scan (which also carries Relation Name in PG
        // JSON) and a nameless Seq Scan child: only a Seq Scan may source the title.
        var indexChild = planNodeWithTable("Index Scan", 10, 10, "secret_table");
        var seqChild = planNode("Seq Scan", 100, 100); // bare, no structured name
        var parent = new PlanNode("Hash Join", 0.0, 0.0, 100, 100, 0,
            List.of(indexChild, seqChild));
        var fragment = fragment("f1", "Original Query");

        List<DiagnosticFinding> findings = rule.analyze(fragment, parent, timing(50));

        assertEquals(1, findings.size());
        assertFalse(findings.get(0).title().contains("secret_table"),
            "Index Scan's Relation Name must not be claimed by the seq-scan finding, got: "
                + findings.get(0).title());
        // Neither source present → documented label fallback stays intact.
        assertTrue(findings.get(0).title().contains("unknown relation"),
            "With no relation evidence, identify the relation as unknown, got: "
                + findings.get(0).title());
    }

    @Test
    void pgJsonChannel_titleUsesStructuredTableName() {
        // Verbatim live-captured shape of the e2e flow 4 EXPLAIN JSON: bare
        // "Node Type", relation carried in "Relation Name".
        String explainJson = """
            [{"Plan": {
                "Node Type": "Seq Scan",
                "Parallel Aware": false,
                "Relation Name": "sqldiag_it_orders",
                "Alias": "sqldiag_it_orders",
                "Startup Cost": 0.00,
                "Total Cost": 18.75,
                "Plan Rows": 233,
                "Plan Width": 44,
                "Actual Startup Time": 0.005,
                "Actual Total Time": 0.012,
                "Actual Rows": 2,
                "Actual Loops": 1,
                "Filter": "(amount > '100'::numeric)",
                "Rows Removed by Filter": 18
            }}]""";

        PlanNode plan = new PostgreSqlPlanAdapter().parse(explainJson, "postgresql");
        assertNotNull(plan);
        // JSON channel pins: operation is BARE (landmine B), no "on <table>" text.
        assertEquals("Seq Scan", plan.operation());
        var fragment = fragment("root-1", "Original Query");

        List<DiagnosticFinding> findings = rule.analyze(fragment, plan, timing(20));

        assertEquals(1, findings.size());
        assertTrue(findings.get(0).title().contains("sqldiag_it_orders"),
            "Title should carry the structured table name, got: " + findings.get(0).title());
        assertFalse(findings.get(0).title().contains("unknown relation"),
            "Title must not fall back to the fragment label when the table name is known, got: "
                + findings.get(0).title());
    }

    @Test
    void pgJsonChannel_nestedChildSeqScan_titleUsesStructuredTableName() {
        // Hash Join parent, Seq Scan child carrying the structured name.
        String explainJson = """
            [{"Plan": {
                "Node Type": "Hash Join",
                "Startup Cost": 1.0, "Total Cost": 100.0,
                "Plan Rows": 50, "Actual Rows": 40, "Actual Total Time": 5.0,
                "Plans": [
                    {
                        "Node Type": "Seq Scan",
                        "Relation Name": "line_items",
                        "Startup Cost": 0.0, "Total Cost": 10.0,
                        "Plan Rows": 100, "Actual Rows": 100, "Actual Total Time": 0.5
                    },
                    {
                        "Node Type": "Hash",
                        "Startup Cost": 0.0, "Total Cost": 20.0,
                        "Plan Rows": 200, "Actual Rows": 200, "Actual Total Time": 1.0
                    }
                ]
            }}]""";

        PlanNode plan = new PostgreSqlPlanAdapter().parse(explainJson, "postgresql");
        assertNotNull(plan);
        var fragment = fragment("root-2", "Original CTE Query");

        List<DiagnosticFinding> findings = rule.analyze(fragment, plan, timing(50));

        assertEquals(1, findings.size());
        assertTrue(findings.get(0).title().contains("line_items"),
            "Child Seq Scan's structured table name should win, got: " + findings.get(0).title());
    }

    @Test
    void mysqlJsonChannel_titleUsesStructuredTableName() {
        // Same landmine class on the MySQL JSON channel: access_type "ALL" is
        // normalized to canonical "Seq Scan" (MySqlPlanAdapter OPERATION_MAP) —
        // bare, no table suffix — while the relation sits in "table_name".
        String explainJson = """
            {
                "query_block": {
                    "select_id": 1,
                    "cost_info": { "query_cost": "2.00" },
                    "table": {
                        "table_name": "orders",
                        "access_type": "ALL",
                        "rows_examined_per_scan": 100,
                        "cost_info": { "prefix_cost": "1.50" }
                    }
                }
            }""";

        PlanNode plan = new MySqlPlanAdapter().parse(explainJson, "mysql");
        assertNotNull(plan);
        assertEquals("Seq Scan", plan.operation());
        var fragment = fragment("root-3", "Original Query");

        List<DiagnosticFinding> findings = rule.analyze(fragment, plan, timing(15));

        assertEquals(1, findings.size());
        assertTrue(findings.get(0).title().contains("orders"),
            "MySQL channel should also carry the structured table name, got: "
                + findings.get(0).title());
    }
}
