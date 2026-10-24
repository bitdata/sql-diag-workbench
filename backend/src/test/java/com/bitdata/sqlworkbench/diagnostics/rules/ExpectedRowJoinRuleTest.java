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
import com.bitdata.sqlworkbench.sql.model.JoinInfo;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.bitdata.sqlworkbench.diagnostics.rules.DiagnosticRuleTestFixture.*;
import static org.junit.jupiter.api.Assertions.*;

class ExpectedRowJoinRuleTest {

    private final ExpectedRowEliminatedAtJoinRule rule = new ExpectedRowEliminatedAtJoinRule();

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
    void firesWhenAddedJoins() {
        TraceStep child = new TraceStep(0, "child", "Split",
            "SELECT * FROM a JOIN b ON a.id = b.a_id", TraceStatus.HAS_ROWS, -1, 10, null);
        TraceStep parent = new TraceStep(1, "parent", "Original",
            "SELECT * FROM a JOIN b ON a.id = b.a_id JOIN c ON b.id = c.b_id",
            TraceStatus.NO_ROWS, -1, 10, null);
        EliminationEdge edge = new EliminationEdge(child, parent,
            List.of(),
            List.of(new JoinInfo("c", "c", org.apache.calcite.sql.JoinType.INNER, "b.id = c.b_id", 2)),
            EliminationEdge.Kind.JOIN);

        List<DiagnosticFinding> findings = rule.analyzeTrace(traceContext(parent, child, edge));

        assertEquals(1, findings.size());
    }

    @Test
    void doesNotFireWhenNoAddedJoins() {
        TraceStep child = new TraceStep(0, "child", "Split",
            "SELECT * FROM a", TraceStatus.HAS_ROWS, -1, 10, null);
        TraceStep parent = new TraceStep(1, "parent", "Original",
            "SELECT * FROM a JOIN b ON a.id = b.a_id", TraceStatus.NO_ROWS, -1, 10, null);
        EliminationEdge edge = new EliminationEdge(child, parent,
            List.of(), List.of(), EliminationEdge.Kind.NONE);

        assertTrue(rule.analyzeTrace(traceContext(parent, child, edge)).isEmpty());
    }
}
