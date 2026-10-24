package com.bitdata.sqlworkbench.diagnostics;

import com.bitdata.sqlworkbench.execution.model.ExecutionResult.PlanNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Shared utility for recursive PlanNode tree traversal.
 * Replaces ad-hoc recursion in individual diagnostic rules.
 */
public final class PlanNodeWalker {

    private PlanNodeWalker() {}

    /** Depth-first traversal, calling visitor on each node. */
    public static void walk(PlanNode root, Consumer<PlanNode> visitor) {
        if (root == null) return;
        visitor.accept(root);
        if (root.children() != null) {
            for (PlanNode child : root.children()) {
                walk(child, visitor);
            }
        }
    }

    /** Find all nodes matching predicate. */
    public static List<PlanNode> findAll(PlanNode root, Predicate<PlanNode> predicate) {
        List<PlanNode> result = new ArrayList<>();
        walk(root, node -> {
            if (predicate.test(node)) result.add(node);
        });
        return result;
    }

    /** Find first matching node (depth-first). */
    public static Optional<PlanNode> findFirst(PlanNode root, Predicate<PlanNode> predicate) {
        if (root == null) return Optional.empty();
        if (predicate.test(root)) return Optional.of(root);
        if (root.children() != null) {
            for (PlanNode child : root.children()) {
                Optional<PlanNode> found = findFirst(child, predicate);
                if (found.isPresent()) return found;
            }
        }
        return Optional.empty();
    }

    /** Collect all leaf nodes (no children). */
    public static List<PlanNode> leaves(PlanNode root) {
        return findAll(root, node -> node.children() == null || node.children().isEmpty());
    }

    /** Legacy unsupported operation: arbitrary operator counts cannot be summed. */
    public static long sumActualRows(List<PlanNode> nodes) {
        // Counts across arbitrary operators/loop averages are not additive.
        throw new IllegalArgumentException("INCOMPARABLE_OPERATOR_COUNTS");
    }
}
