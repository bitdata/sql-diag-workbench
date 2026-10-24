package com.bitdata.sqlworkbench.diagnostics;

import com.bitdata.sqlworkbench.execution.model.ExecutionResult.PlanNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PlanNodeWalkerTest {

    private PlanNode leaf(String op, long planRows, long actualRows) {
        return new PlanNode(op, 0, 0, planRows, actualRows, 0, null);
    }

    private PlanNode node(String op, long planRows, long actualRows, List<PlanNode> children) {
        return new PlanNode(op, 0, 0, planRows, actualRows, 0, children);
    }

    @Test
    void walkVisitsAllNodesDepthFirst() {
        PlanNode child1 = leaf("Seq Scan on orders", 100, 90);
        PlanNode child2 = leaf("Index Scan on customers", 50, 48);
        PlanNode root = node("Hash Join", 200, 180, List.of(child1, child2));

        List<String> visited = new ArrayList<>();
        PlanNodeWalker.walk(root, n -> visited.add(n.operation()));

        assertEquals(List.of("Hash Join", "Seq Scan on orders", "Index Scan on customers"), visited);
    }

    @Test
    void walkHandlesNullRoot() {
        List<String> visited = new ArrayList<>();
        PlanNodeWalker.walk(null, n -> visited.add(n.operation()));
        assertTrue(visited.isEmpty());
    }

    @Test
    void findAllReturnsMatchingNodes() {
        PlanNode seqScan = leaf("Seq Scan on orders", 100, 90);
        PlanNode idxScan = leaf("Index Scan on customers", 50, 48);
        PlanNode root = node("Hash Join", 200, 180, List.of(seqScan, idxScan));

        List<PlanNode> scans = PlanNodeWalker.findAll(root,
            n -> n.operation() != null && n.operation().startsWith("Seq Scan"));

        assertEquals(1, scans.size());
        assertEquals("Seq Scan on orders", scans.get(0).operation());
    }

    @Test
    void findFirstReturnsFirstMatch() {
        PlanNode seqScan1 = leaf("Seq Scan on orders", 100, 90);
        PlanNode seqScan2 = leaf("Seq Scan on products", 200, 180);
        PlanNode root = node("Append", 300, 270, List.of(seqScan1, seqScan2));

        var result = PlanNodeWalker.findFirst(root,
            n -> n.operation() != null && n.operation().startsWith("Seq Scan"));

        assertTrue(result.isPresent());
        assertEquals("Seq Scan on orders", result.get().operation());
    }

    @Test
    void findFirstReturnsEmptyWhenNoMatch() {
        PlanNode root = leaf("Index Scan on orders", 100, 90);

        var result = PlanNodeWalker.findFirst(root,
            n -> n.operation() != null && n.operation().startsWith("Seq Scan"));

        assertTrue(result.isEmpty());
    }

    @Test
    void leavesReturnsOnlyLeafNodes() {
        PlanNode leaf1 = leaf("Seq Scan on a", 10, 10);
        PlanNode leaf2 = leaf("Seq Scan on b", 20, 20);
        PlanNode mid = node("Nested Loop", 30, 30, List.of(leaf1));
        PlanNode root = node("Hash Join", 50, 50, List.of(mid, leaf2));

        List<PlanNode> result = PlanNodeWalker.leaves(root);

        assertEquals(2, result.size());
        assertEquals("Seq Scan on a", result.get(0).operation());
        assertEquals("Seq Scan on b", result.get(1).operation());
    }

    @Test
    void sumActualRowsPrefersActualOverPlan() {
        PlanNode child1 = leaf("Scan", 100, 90);   // actualRows=90
        PlanNode child2 = leaf("Scan", 200, 0);    // actualRows=0, fallback to planRows=200

        assertThrows(IllegalArgumentException.class,() -> PlanNodeWalker.sumActualRows(List.of(child1, child2)));
    }

    @Test
    void sumActualRowsEmptyList() {
        assertThrows(IllegalArgumentException.class,() -> PlanNodeWalker.sumActualRows(List.of()));
    }
}
