package com.bitdata.sqlworkbench.diagnostics.trace;

import com.bitdata.sqlworkbench.sql.model.FragmentNode;
import com.bitdata.sqlworkbench.sql.model.FragmentType;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class DagWalkerTest {

    private final DagWalker walker = new DagWalker();

    private FragmentNode node(String id, String... childIds) {
        List<FragmentNode> children = new ArrayList<>();
        return new FragmentNode(id, FragmentType.ORIGINAL, id, "SELECT 1",
            true, null, childIds.length, null, children);
    }

    private FragmentNode nodeWithChildren(String id, List<FragmentNode> children) {
        return new FragmentNode(id, FragmentType.ORIGINAL, id, "SELECT 1",
            true, null, children.size(), null, children);
    }

    @Test
    void topologicalSort_singleNode() {
        FragmentNode root = node("root");
        Map<String, FragmentNode> nodes = Map.of("root", root);
        List<String> sorted = walker.topologicalSort(nodes);
        assertEquals(List.of("root"), sorted);
    }

    @Test
    void topologicalSort_linearChain() {
        // leaf → mid → root
        FragmentNode leaf = node("leaf");
        FragmentNode mid = nodeWithChildren("mid", List.of(leaf));
        FragmentNode root = nodeWithChildren("root", List.of(mid));

        Map<String, FragmentNode> nodes = Map.of("root", root, "mid", mid, "leaf", leaf);
        List<String> sorted = walker.topologicalSort(nodes);
        assertEquals(List.of("leaf", "mid", "root"), sorted);
    }

    @Test
    void topologicalSort_multiLeafJoin() {
        FragmentNode left = node("left_leaf");
        FragmentNode right = node("right_leaf");
        FragmentNode join = nodeWithChildren("join", List.of(left, right));
        FragmentNode root = nodeWithChildren("root", List.of(join));

        Map<String, FragmentNode> nodes = Map.of(
            "root", root, "join", join, "left_leaf", left, "right_leaf", right);
        List<String> sorted = walker.topologicalSort(nodes);

        assertTrue(sorted.indexOf("left_leaf") < sorted.indexOf("join"));
        assertTrue(sorted.indexOf("right_leaf") < sorted.indexOf("join"));
        assertTrue(sorted.indexOf("join") < sorted.indexOf("root"));
        assertEquals(4, sorted.size());
    }

    @Test
    void topologicalSort_sharedAncestor() {
        FragmentNode la = node("leaf_a");
        FragmentNode lb = node("leaf_b");
        FragmentNode lc = node("leaf_c");
        FragmentNode midA = nodeWithChildren("mid_a", List.of(la, lb));
        FragmentNode midB = nodeWithChildren("mid_b", List.of(lc));
        FragmentNode root = nodeWithChildren("root", List.of(midA, midB));

        Map<String, FragmentNode> nodes = Map.of(
            "root", root, "mid_a", midA, "mid_b", midB,
            "leaf_a", la, "leaf_b", lb, "leaf_c", lc);
        List<String> sorted = walker.topologicalSort(nodes);

        assertTrue(sorted.indexOf("leaf_a") < sorted.indexOf("mid_a"));
        assertTrue(sorted.indexOf("leaf_b") < sorted.indexOf("mid_a"));
        assertTrue(sorted.indexOf("leaf_c") < sorted.indexOf("mid_b"));
        assertTrue(sorted.indexOf("mid_a") < sorted.indexOf("root"));
        assertTrue(sorted.indexOf("mid_b") < sorted.indexOf("root"));
        assertEquals(6, sorted.size());
    }

    @Test
    void topologicalSort_deepTree() {
        FragmentNode n1 = node("n1");
        FragmentNode n2 = nodeWithChildren("n2", List.of(n1));
        FragmentNode n3 = nodeWithChildren("n3", List.of(n2));
        FragmentNode n4 = nodeWithChildren("n4", List.of(n3));
        FragmentNode n5 = nodeWithChildren("n5", List.of(n4));

        Map<String, FragmentNode> nodes = Map.of(
            "n1", n1, "n2", n2, "n3", n3, "n4", n4, "n5", n5);
        List<String> sorted = walker.topologicalSort(nodes);

        assertEquals(List.of("n1", "n2", "n3", "n4", "n5"), sorted);
    }

    @Test
    void topologicalSort_emptyMap() {
        List<String> sorted = walker.topologicalSort(Map.of());
        assertTrue(sorted.isEmpty());
    }
}
