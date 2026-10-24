package com.bitdata.sqlworkbench.execution.model;

import com.bitdata.sqlworkbench.execution.model.ExecutionResult.PlanNode;
import com.bitdata.sqlworkbench.execution.model.ExecutionResult.PlanNodeConditions;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Round-trip serialization tests for {@link PlanNode} and
 * {@link PlanNodeConditions} (live-channel-assumption-sweep 2026-09-09 bug ⑧).
 *
 * <p>Pins the wire contract that {@code src/renderer/types/execution.ts}
 * mirrors. If anyone adds a new field and forgets to update the JSON
 * serialization (or the frontend type), one of these tests goes red.
 */
class PlanNodeSerializationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void roundTrip_preservesAllNineFields() throws Exception {
        PlanNodeConditions conds = new PlanNodeConditions(
            "(x > 5)",
            "(y = 10)",
            "(a = b)",
            "(c <> d)"
        );
        PlanNode original = new PlanNode(
            "Filter", 0.15, 0.50, 100L, 80L, 1L,
            List.of(), "orders", conds
        );

        String json = mapper.writeValueAsString(original);
        PlanNode restored = mapper.readValue(json, PlanNode.class);

        assertEquals(original.operation(), restored.operation());
        assertEquals(original.startupCost(), restored.startupCost(), 1e-9);
        assertEquals(original.totalCost(), restored.totalCost(), 1e-9);
        assertEquals(original.planRows(), restored.planRows());
        assertEquals(original.actualRows(), restored.actualRows());
        assertEquals(original.actualDuration(), restored.actualDuration());
        assertEquals(original.tableName(), restored.tableName());

        assertNotNull(restored.conditions(), "conditions must not be lost on round-trip");
        assertEquals("(x > 5)", restored.conditions().filter());
        assertEquals("(y = 10)", restored.conditions().indexCond());
        assertEquals("(a = b)", restored.conditions().hashCond());
        assertEquals("(c <> d)", restored.conditions().joinFilter());
    }

    @Test
    void roundTrip_preservesEmptyConditions() throws Exception {
        // Adapter-miss path: legacy 7-arg constructor produces an EMPTY
        // PlanNodeConditions (non-null record, all fields null). Make sure
        // this survives JSON round-trip as a non-null record.
        PlanNode original = new PlanNode(
            "Seq Scan", 0.0, 35.5, 2340L, 2000L, 1L, List.of()
        );

        String json = mapper.writeValueAsString(original);
        PlanNode restored = mapper.readValue(json, PlanNode.class);

        assertNotNull(restored.conditions(),
            "Legacy constructor leaves conditions = EMPTY; round-trip must keep non-null record");
        assertNull(restored.conditions().filter());
        assertNull(restored.conditions().indexCond());
        assertNull(restored.conditions().hashCond());
        assertNull(restored.conditions().joinFilter());
        assertNull(restored.tableName(),
            "Legacy 7-arg constructor leaves tableName null — round-trip must preserve null");
    }

    @Test
    void roundTrip_preservesPartialConditions() throws Exception {
        // Only `filter` populated; the other three fields null. PG often
        // emits a Filter with no Index Cond / Hash Cond / Join Filter — this
        // is the common case.
        PlanNodeConditions onlyFilter = new PlanNodeConditions("(status = 'active')", null, null, null);
        PlanNode original = new PlanNode(
            "Filter", 0.0, 0.0, 50L, 30L, 0L, List.of(), null, onlyFilter
        );

        String json = mapper.writeValueAsString(original);
        PlanNode restored = mapper.readValue(json, PlanNode.class);

        assertNotNull(restored.conditions());
        assertEquals("(status = 'active')", restored.conditions().filter());
        assertNull(restored.conditions().indexCond());
        assertNull(restored.conditions().hashCond());
        assertNull(restored.conditions().joinFilter());
    }

    @Test
    void legacy7ArgConstructor_yieldsEmptyConditions() {
        // Pin the legacy back-compat contract: 7-arg callers do NOT supply
        // conditions, but the field is always present (EMPTY sentinel) so
        // downstream consumers don't need a null guard — they only check
        // individual field values within conditions.
        PlanNode legacy = new PlanNode(
            "Seq Scan", 0.0, 35.5, 2340L, 2000L, 1L, List.of()
        );

        assertNotNull(legacy.conditions(),
            "7-arg legacy constructor must default conditions to EMPTY (non-null record with all-null fields)");
        assertEquals(PlanNodeConditions.EMPTY, legacy.conditions(),
            "EMPTY sentinel ensures all four fields default to null");
    }

    @Test
    void legacy8ArgConstructor_yieldsEmptyConditions() {
        PlanNode legacy = new PlanNode(
            "Seq Scan", 0.0, 35.5, 2340L, 2000L, 1L, List.of(), "orders"
        );

        assertEquals(PlanNodeConditions.EMPTY, legacy.conditions(),
            "8-arg legacy constructor must default conditions to EMPTY until adapter is updated");
        assertEquals("orders", legacy.tableName(),
            "8-arg legacy constructor preserves tableName (pre-PR-3 contract)");
    }
}
