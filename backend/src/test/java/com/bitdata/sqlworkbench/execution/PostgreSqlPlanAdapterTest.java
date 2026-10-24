package com.bitdata.sqlworkbench.execution;

import com.bitdata.sqlworkbench.execution.model.ExecutionResult.PlanNode;
import com.bitdata.sqlworkbench.execution.model.ExecutionResult.PlanNodeConditions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PostgreSqlPlanAdapterTest {

    private final PostgreSqlPlanAdapter adapter = new PostgreSqlPlanAdapter();

    @Test
    void dialect_returnsPostgresql() {
        assertEquals("postgresql", adapter.dialect());
    }

    @Test
    void parseSimpleSeqScan() {
        String json = """
            [{"Plan": {
                "Node Type": "Seq Scan",
                "Startup Cost": 0.0,
                "Total Cost": 35.5,
                "Plan Rows": 2340,
                "Actual Rows": 2000,
                "Actual Total Time": 1.234,
                "Plans": []
            }}]""";

        PlanNode plan = adapter.parse(json, "postgresql");

        assertNotNull(plan);
        assertEquals("Seq Scan", plan.operation());
        assertEquals(0.0, plan.startupCost());
        assertEquals(35.5, plan.totalCost());
        assertEquals(2340, plan.planRows());
        assertEquals(2000, plan.actualRows());
        assertEquals(1, plan.actualDuration()); // truncated from 1.234
        assertTrue(plan.children().isEmpty());
    }

    @Test
    void parseNestedPlans() {
        String json = """
            [{"Plan": {
                "Node Type": "Hash Join",
                "Startup Cost": 1.0,
                "Total Cost": 100.0,
                "Plan Rows": 50,
                "Actual Rows": 40,
                "Actual Total Time": 5.0,
                "Plans": [
                    {
                        "Node Type": "Seq Scan",
                        "Startup Cost": 0.0,
                        "Total Cost": 10.0,
                        "Plan Rows": 100,
                        "Actual Rows": 100,
                        "Actual Total Time": 0.5,
                        "Plans": []
                    },
                    {
                        "Node Type": "Hash",
                        "Startup Cost": 0.0,
                        "Total Cost": 20.0,
                        "Plan Rows": 200,
                        "Actual Rows": 200,
                        "Actual Total Time": 1.0,
                        "Plans": []
                    }
                ]
            }}]""";

        PlanNode plan = adapter.parse(json, "postgresql");

        assertNotNull(plan);
        assertEquals("Hash Join", plan.operation());
        assertEquals(2, plan.children().size());
        assertEquals("Seq Scan", plan.children().get(0).operation());
        assertEquals("Hash", plan.children().get(1).operation());
    }

    @Test
    void parseSeqScan_capturesStructuredTableName() {
        // E2E landmine D (2026-09-09): "Relation Name" is the ONLY table-name
        // source on this channel — operation is the bare "Node Type". Key name
        // pinned by LIVE probe on the seeded e2e DB; PG never emits "Table Name"
        // (task-e-report §3.4's assumption, corrected by recon — see
        // task-cd-report.md).
        String json = """
            [{"Plan": {
                "Node Type": "Seq Scan",
                "Parallel Aware": false,
                "Relation Name": "orders",
                "Alias": "o",
                "Startup Cost": 0.0,
                "Total Cost": 35.5,
                "Plan Rows": 2340,
                "Actual Rows": 2000,
                "Actual Total Time": 1.234
            }}]""";

        PlanNode plan = adapter.parse(json, "postgresql");

        assertNotNull(plan);
        assertEquals("Seq Scan", plan.operation());
        assertEquals("orders", plan.tableName());
    }

    @Test
    void parseNested_childSeqScanCarriesTableName_parentDoesNot() {
        String json = """
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
                    }
                ]
            }}]""";

        PlanNode plan = adapter.parse(json, "postgresql");

        assertNotNull(plan);
        assertNull(plan.tableName(), "join node has no Relation Name field");
        assertEquals("line_items", plan.children().get(0).tableName());
    }

    @Test
    void parseScanNode_withBlankRelationName_normalizesToNull() {
        String json = """
            [{"Plan": {
                "Node Type": "Seq Scan",
                "Relation Name": "   ",
                "Startup Cost": 0.0, "Total Cost": 1.0,
                "Plan Rows": 1, "Actual Rows": 1, "Actual Total Time": 0.1
            }}]""";

        PlanNode plan = adapter.parse(json, "postgresql");

        assertNotNull(plan);
        assertNull(plan.tableName(), "blank relation name must not masquerade as a real one");
    }

    @Test
    void legacyTableNameKey_isNotRead() {
        // Negative pin: "Table Name" is NOT part of PG's JSON EXPLAIN vocabulary —
        // reading it (the original landmine-D draft) silently yields null and
        // regresses the e2e title to the fragment label. If PG ever adds the key,
        // flip this test and extend the adapter deliberately.
        String json = """
            [{"Plan": {
                "Node Type": "Seq Scan",
                "Table Name": "phantom",
                "Startup Cost": 0.0, "Total Cost": 1.0,
                "Plan Rows": 1, "Actual Rows": 1, "Actual Total Time": 0.1
            }}]""";

        PlanNode plan = adapter.parse(json, "postgresql");

        assertNotNull(plan);
        assertNull(plan.tableName());
    }

    @Test
    void parseNullInput_returnsNull() {
        assertNull(adapter.parse(null, "postgresql"));
    }

    @Test
    void parseEmptyString_returnsNull() {
        assertNull(adapter.parse("", "postgresql"));
    }

    @Test
    void parseInvalidJson_returnsNull() {
        assertNull(adapter.parse("not json", "postgresql"));
    }

    @Test
    void parseEmptyArray_returnsNull() {
        assertNull(adapter.parse("[]", "postgresql"));
    }

    // ----- bug ⑧: structured predicate text (live-channel-assumption-sweep 2026-09-09) -----

    @Test
    void parseNode_populatesFilterText() {
        String json = """
            [{"Plan": {
                "Node Type": "Filter",
                "Filter": "(status = 'active')",
                "Plans": []
            }}]""";

        PlanNode plan = adapter.parse(json, "postgresql");

        assertNotNull(plan);
        assertNotNull(plan.conditions());
        assertEquals("(status = 'active')", plan.conditions().filter());
        assertNull(plan.conditions().indexCond());
        assertNull(plan.conditions().hashCond());
        assertNull(plan.conditions().joinFilter());
    }

    @Test
    void parseNode_populatesIndexCond() {
        String json = """
            [{"Plan": {
                "Node Type": "Index Scan",
                "Index Cond": "(id = 42)",
                "Plans": []
            }}]""";

        PlanNode plan = adapter.parse(json, "postgresql");

        assertNotNull(plan.conditions());
        assertNull(plan.conditions().filter());
        assertEquals("(id = 42)", plan.conditions().indexCond());
    }

    @Test
    void parseNode_populatesHashCond() {
        String json = """
            [{"Plan": {
                "Node Type": "Hash Join",
                "Hash Cond": "(a.id = b.id)",
                "Plans": []
            }}]""";

        PlanNode plan = adapter.parse(json, "postgresql");

        assertNotNull(plan.conditions());
        assertEquals("(a.id = b.id)", plan.conditions().hashCond());
    }

    @Test
    void parseNode_populatesJoinFilter() {
        String json = """
            [{"Plan": {
                "Node Type": "Nested Loop",
                "Join Filter": "(a.cat = b.cat)",
                "Plans": []
            }}]""";

        PlanNode plan = adapter.parse(json, "postgresql");

        assertNotNull(plan.conditions());
        assertEquals("(a.cat = b.cat)", plan.conditions().joinFilter());
    }

    @Test
    void parseNode_absentFields_yieldNullsNotBlanks() {
        // No Filter / Index Cond / Hash Cond / Join Filter keys present.
        String json = """
            [{"Plan": {
                "Node Type": "Seq Scan",
                "Plans": []
            }}]""";

        PlanNode plan = adapter.parse(json, "postgresql");

        assertNotNull(plan.conditions());
        assertEquals(PlanNodeConditions.EMPTY, plan.conditions());
    }

    @Test
    void parseNode_emptyStringFilterCoercedToNull() {
        // PG sometimes emits "Filter": "" — defensive coercion.
        String json = """
            [{"Plan": {
                "Node Type": "Filter",
                "Filter": "",
                "Plans": []
            }}]""";

        PlanNode plan = adapter.parse(json, "postgresql");

        assertNotNull(plan.conditions());
        assertNull(plan.conditions().filter(),
            "Empty string predicate must be coerced to null (Rule #2: observable degradation)");
    }
}
