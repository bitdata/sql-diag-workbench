package com.bitdata.sqlworkbench.diagnostics.rules;

import com.bitdata.sqlworkbench.diagnostics.DiagnosticFinding;
import com.bitdata.sqlworkbench.diagnostics.trace.EliminationEdge;
import com.bitdata.sqlworkbench.diagnostics.trace.EliminationOutcome;
import com.bitdata.sqlworkbench.diagnostics.trace.KeyValue;
import com.bitdata.sqlworkbench.diagnostics.trace.TraceContext;
import com.bitdata.sqlworkbench.diagnostics.trace.TraceResult;
import com.bitdata.sqlworkbench.diagnostics.trace.TraceStatus;
import com.bitdata.sqlworkbench.diagnostics.trace.TraceStep;
import com.bitdata.sqlworkbench.execution.model.ExecutionResult.PlanNode;
import com.bitdata.sqlworkbench.sql.model.WhereInfo;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.bitdata.sqlworkbench.diagnostics.rules.DiagnosticRuleTestFixture.*;
import static org.junit.jupiter.api.Assertions.*;

class ExpectedRowFilterRuleTest {

    private final ExpectedRowEliminatedAtFilterRule rule = new ExpectedRowEliminatedAtFilterRule();

    // ── analyze() path ────────────────────────────────────────────────

    @Test
    void aggregateCountsDoNotProveCausality() {
        for(String op:List.of("Filter","Hash Join","Nested Loop","Result","Seq Scan")) {
            for(long actual:List.of(0L,1L,100000L)) {
                var child=planNode("Seq Scan",100,100);
                var parent=planNode(op,100,actual,List.of(child));
                assertTrue(rule.analyze(fragment("f1","sample"),parent,timing(10)).isEmpty(),op+"/"+actual);
            }
        }
    }

    // ── analyzeTrace() path ────────────────────────────────────────────

    private TraceContext traceContext(TraceStep parent, TraceStep child, EliminationEdge edge) {
        TraceResult result = new TraceResult(
            List.of(child, parent), parent, 20L, null, List.of(),
            new EliminationOutcome.Edge(child, parent), edge, null);
        return new TraceContext("c1", null, Map.of(), result,
            List.of(new KeyValue("order_id", 12345)));
    }

    @Test
    void originalElimPointWithSingleWhere_firesViaEdgeDiff() {
        // parent.type = ORIGINAL（单 WHERE）——旧 node-type 匹配归因不了；
        // edge diff 必须无视类型、凭 addedWhere 触发。
        TraceStep child = new TraceStep(0, "child", "Stripped", "SELECT * FROM orders",
            TraceStatus.HAS_ROWS, -1, 10, null);
        TraceStep parent = new TraceStep(1, "parent", "Original",
            "SELECT * FROM orders WHERE a = 1", TraceStatus.NO_ROWS, -1, 10, null);
        EliminationEdge edge = new EliminationEdge(child, parent,
            List.of(new WhereInfo("a = 1", 0)), List.of(), EliminationEdge.Kind.FILTER);

        List<DiagnosticFinding> findings = rule.analyzeTrace(traceContext(parent, child, edge));

        assertEquals(1, findings.size());
        assertTrue(findings.get(0).message().contains("a = 1"),
            "message must cite the added predicate: " + findings.get(0).message());
    }

    @Test
    void strippedOrderBy_doesNotFireFilter() {
        // ORDER-BY-strip 无 addedWhere → filter 规则不触发（K1）。
        TraceStep child = new TraceStep(0, "child", "Stripped", "SELECT * FROM orders",
            TraceStatus.HAS_ROWS, -1, 10, null);
        TraceStep parent = new TraceStep(1, "parent", "Stripped",
            "SELECT * FROM orders ORDER BY id", TraceStatus.NO_ROWS, -1, 10, null);
        EliminationEdge edge = new EliminationEdge(child, parent,
            List.of(), List.of(), EliminationEdge.Kind.NONE);

        List<DiagnosticFinding> findings = rule.analyzeTrace(traceContext(parent, child, edge));

        assertTrue(findings.isEmpty());
    }
}
