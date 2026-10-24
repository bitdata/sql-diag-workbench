package com.bitdata.sqlworkbench.diagnostics.trace;

import com.bitdata.sqlworkbench.sql.model.FragmentNode;

import java.util.*;

/**
 * Topological sort of the fragment DAG in leaf-first order.
 * Uses Kahn's algorithm with in-degree = children.size().
 * Leaves (no children) have in-degree 0 and are processed first.
 */
public class DagWalker {

    /**
     * Return node IDs in leaf-first topological order.
     * A node appears only after all its children have appeared.
     *
     * @throws IllegalStateException if the graph contains a cycle
     */
    public List<String> topologicalSort(Map<String, FragmentNode> nodes) {
        if (nodes.isEmpty()) return List.of();

        // in-degree = number of children
        Map<String, Integer> inDegree = new HashMap<>();
        // child → list of parent IDs
        Map<String, List<String>> childToParents = new HashMap<>();

        for (var entry : nodes.entrySet()) {
            String id = entry.getKey();
            FragmentNode node = entry.getValue();
            int childCount = (node.children() != null) ? (int)node.children().stream().map(FragmentNode::id).distinct().count() : 0;
            inDegree.put(id, childCount);

            if (node.children() != null) {
                for (FragmentNode child : node.children()) {
                    childToParents
                        .computeIfAbsent(child.id(), k -> new ArrayList<>())
                        .add(id);
                }
            }
        }

        Queue<String> queue = new PriorityQueue<>();
        for (var entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) {
                queue.add(entry.getKey());
            }
        }

        List<String> result = new ArrayList<>();
        while (!queue.isEmpty()) {
            String current = queue.poll();
            result.add(current);

            List<String> parents = childToParents.getOrDefault(current, List.of());
            for (String parentId : new TreeSet<>(parents)) {
                int newDegree = inDegree.get(parentId) - 1;
                inDegree.put(parentId, newDegree);
                if (newDegree == 0) {
                    queue.add(parentId);
                }
            }
        }

        if (result.size() != nodes.size()) {
            throw new IllegalStateException(
                "Cyclic fragment graph detected: processed " + result.size()
                + " of " + nodes.size() + " nodes");
        }

        return result;
    }
}
