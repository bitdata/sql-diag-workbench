package com.bitdata.sqlworkbench.diagnostics.trace;

import com.bitdata.sqlworkbench.sql.DialectProfile;
import com.bitdata.sqlworkbench.sql.SqlParseEngine;
import com.bitdata.sqlworkbench.sql.decompose.RuleTestDeps;
import com.bitdata.sqlworkbench.sql.model.FragmentNode;
import com.bitdata.sqlworkbench.sql.model.FragmentType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EliminationEdgeFactoryTest {

    private final SqlParseEngine parseEngine = new SqlParseEngine(RuleTestDeps.testResolver());
    private final EliminationEdgeFactory factory = new EliminationEdgeFactory(parseEngine);
    private final DialectProfile profile = RuleTestDeps.testResolver().getProfile("postgresql");

    private FragmentNode node(String id, String sql) {
        return new FragmentNode(id, FragmentType.ORIGINAL, id, sql, true, null, 0, null, List.of());
    }

    private TraceStep step(int idx, String id, TraceStatus status) {
        return new TraceStep(idx, id, id, "", status, -1, 10, null);
    }

    @Test
    void whereEdge_attributesAddedPredicate() {
        FragmentNode parent = node("parent", "SELECT * FROM orders WHERE a = 1");
        FragmentNode child = node("child", "SELECT * FROM orders");
        EliminationEdge edge = factory.build(
            new EliminationOutcome.Edge(step(0, "child", TraceStatus.HAS_ROWS),
                step(1, "parent", TraceStatus.NO_ROWS)),
            Map.of("parent", parent, "child", child), profile, "postgresql");

        assertEquals(EliminationEdge.Kind.FILTER, edge.kind());
        assertEquals(1, edge.addedWhere().size());
        assertTrue(edge.addedWhere().get(0).conditionSql().toUpperCase().contains("A = 1"));
    }

    @Test
    void joinEdge_attributesAddedJoin() {
        FragmentNode parent = node("parent",
            "SELECT * FROM a JOIN b ON a.id = b.a_id JOIN c ON b.id = c.b_id");
        FragmentNode child = node("child",
            "SELECT * FROM a JOIN b ON a.id = b.a_id");
        EliminationEdge edge = factory.build(
            new EliminationOutcome.Edge(step(0, "child", TraceStatus.HAS_ROWS),
                step(1, "parent", TraceStatus.NO_ROWS)),
            Map.of("parent", parent, "child", child), profile, "postgresql");

        assertEquals(EliminationEdge.Kind.JOIN, edge.kind());
        assertEquals(1, edge.addedJoins().size());
        assertTrue(edge.addedJoins().get(0).tableName().equalsIgnoreCase("c"));
    }

    @Test
    void parseFailure_producesKindNone() {
        FragmentNode parent = node("parent", "SELECT * FROM orders WHERE a = 1 AND");
        FragmentNode child = node("child", "SELECT * FROM orders");
        EliminationEdge edge = factory.build(
            new EliminationOutcome.Edge(step(0, "child", TraceStatus.HAS_ROWS),
                step(1, "parent", TraceStatus.NO_ROWS)),
            Map.of("parent", parent, "child", child), profile, "postgresql");

        assertEquals(EliminationEdge.Kind.NONE, edge.kind());
        assertTrue(edge.addedWhere().isEmpty());
        assertTrue(edge.addedJoins().isEmpty());
        assertTrue(edge.degraded());
        assertNotNull(edge.errorReason());
        assertEquals(TraceStatus.HAS_ROWS, edge.childStep().status());
        assertEquals(TraceStatus.NO_ROWS, edge.parentStep().status());
    }
}
