package com.bitdata.sqlworkbench.execution;

import com.bitdata.sqlworkbench.execution.model.ExecutionResult.PlanNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MySqlPlanAdapterTest {

    private final MySqlPlanAdapter adapter = new MySqlPlanAdapter();

    @Test
    void dialect_returnsMysql() {
        assertEquals("mysql", adapter.dialect());
    }

    @Test
    void parseSimpleSelect_allAccessType_mapsToSeqScan() {
        String json = """
            {
                "query_block": {
                    "select_id": 1,
                    "cost_info": { "query_cost": "2.00" },
                    "table": {
                        "table_name": "orders",
                        "access_type": "ALL",
                        "rows_examined_per_scan": 100,
                        "cost_info": {
                            "read_cost": "1.00",
                            "eval_cost": "0.50",
                            "prefix_cost": "1.50"
                        },
                        "attached_condition": "orders.status = 'active'"
                    }
                }
            }""";

        PlanNode plan = adapter.parse(json, "mysql");

        assertNotNull(plan);
        assertEquals("Seq Scan", plan.operation());
        // Normalized operation is bare — "table_name" is the structured source
        // (E2E landmine D class, MySQL JSON channel).
        assertEquals("orders", plan.tableName());
        assertEquals(0.0, plan.startupCost());
        assertEquals(1.5, plan.totalCost());
        assertEquals("PREFIX_ESTIMATE",plan.metrics().totalCost().basis());
        assertEquals(100, plan.planRows());
        assertEquals(0, plan.actualRows()); // estimated mode
        assertEquals(0, plan.actualDuration()); // MySQL has no per-node timing
        // attached_condition creates a Filter child
        assertEquals(1, plan.children().size());
        assertEquals("Filter", plan.children().get(0).operation());
        assertNull(plan.children().get(0).tableName(), "pseudo Filter node carries no table");
    }

    @Test
    void parseIndexScan_refAccessType() {
        String json = """
            {
                "query_block": {
                    "select_id": 1,
                    "table": {
                        "table_name": "users",
                        "access_type": "ref",
                        "rows_examined_per_scan": 10,
                        "cost_info": { "prefix_cost": "0.50" }
                    }
                }
            }""";

        PlanNode plan = adapter.parse(json, "mysql");

        assertNotNull(plan);
        assertEquals("Index Scan", plan.operation());
        assertEquals(10, plan.planRows());
    }

    @Test
    void parseJoinQuery_nestedLoop() {
        String json = """
            {
                "query_block": {
                    "select_id": 1,
                    "nested_loop": [
                        {
                            "table": {
                                "table_name": "orders",
                                "access_type": "ALL",
                                "rows_examined_per_scan": 100,
                                "cost_info": { "prefix_cost": "1.00" }
                            }
                        },
                        {
                            "table": {
                                "table_name": "customers",
                                "access_type": "eq_ref",
                                "rows_examined_per_scan": 1,
                                "cost_info": { "prefix_cost": "2.00" }
                            }
                        }
                    ]
                }
            }""";

        PlanNode plan = adapter.parse(json, "mysql");

        assertNotNull(plan);
        assertEquals("Nested Loop", plan.operation());
        assertEquals(2, plan.children().size());
        assertEquals("Seq Scan", plan.children().get(0).operation());
        assertEquals("Index Scan", plan.children().get(1).operation());
    }

    @Test
    void parseAnalyzeMode_actualRowsPopulated() {
        String json = """
            {
                "query_block": {
                    "select_id": 1,
                    "table": {
                        "table_name": "orders",
                        "access_type": "ALL",
                        "rows_examined_per_scan": 100,
                        "actual_rows": 50,
                        "actual_loops": 1,
                        "cost_info": { "prefix_cost": "1.00" }
                    }
                }
            }""";

        PlanNode plan = adapter.parse(json, "mysql");

        assertNotNull(plan);
        assertEquals(0, plan.actualRows());
        assertEquals("NOT_COLLECTED",plan.metrics().actualRows().state());
    }

    @Test
    void parseUnionQuery_multipleQueryBlocks() {
        String json = """
            {
                "query_block": {
                    "union_result": {
                        "query_specifications": [
                            {
                                "query_block": {
                                    "select_id": 1,
                                    "table": {
                                        "table_name": "t1",
                                        "access_type": "ALL",
                                        "rows_examined_per_scan": 10,
                                        "cost_info": { "prefix_cost": "1.00" }
                                    }
                                }
                            },
                            {
                                "query_block": {
                                    "select_id": 2,
                                    "table": {
                                        "table_name": "t2",
                                        "access_type": "ALL",
                                        "rows_examined_per_scan": 20,
                                        "cost_info": { "prefix_cost": "2.00" }
                                    }
                                }
                            }
                        ]
                    }
                }
            }""";

        PlanNode plan = adapter.parse(json, "mysql");

        assertNotNull(plan);
        assertEquals("Append", plan.operation());
        assertEquals(2, plan.children().size());
    }

    @Test
    void parseNullInput_returnsNull() {
        assertNull(adapter.parse(null, "mysql"));
    }

    @Test
    void parseInvalidJson_returnsNull() {
        assertNull(adapter.parse("not json", "mysql"));
    }

    @Test
    void parseEmptyQueryBlock_returnsNull() {
        String json = """
            { "query_block": {} }""";

        assertEquals("PARTIAL",adapter.parseDetailed(json,"mysql").status());
    }
}
