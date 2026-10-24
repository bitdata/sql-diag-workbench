package com.bitdata.sqlworkbench.diagnostics.trace;

import com.bitdata.sqlworkbench.sql.model.FragmentNode;
import com.bitdata.sqlworkbench.sql.model.FragmentType;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class EliminationDetectorTest {

    private final EliminationDetector detector = new EliminationDetector();

    private FragmentNode node(String id, List<FragmentNode> children) {
        return new FragmentNode(id, FragmentType.ORIGINAL, "Label-" + id, "SELECT 1",
            true, null, children.size(), null, children);
    }

    private TraceStep step(int idx, String id, TraceStatus status) {
        return new TraceStep(idx, id, "Label-" + id, "", status, -1, 10, null);
    }

    private TraceStep stepError(int idx, String id, TraceStatus status, String error) {
        return new TraceStep(idx, id, "Label-" + id, "", status, -1, 10, error);
    }

    @Test
    void outcome_isError_whenStepErrors() {
        FragmentNode leaf = node("leaf", List.of());
        FragmentNode root = node("root", List.of(leaf));
        Map<String, FragmentNode> nodes = Map.of("root", root, "leaf", leaf);

        List<TraceStep> steps = List.of(
            step(0, "leaf", TraceStatus.HAS_ROWS),
            stepError(1, "root", TraceStatus.ERROR, "boom")
        );

        EliminationOutcome outcome = detector.findEliminationOutcome(steps, nodes);
        assertInstanceOf(EliminationOutcome.Error.class, outcome);
        assertEquals("boom", ((EliminationOutcome.Error) outcome).reason());
    }

    @Test
    void outcome_isError_whenStepTimesOut() {
        FragmentNode leaf = node("leaf", List.of());
        FragmentNode root = node("root", List.of(leaf));
        Map<String, FragmentNode> nodes = Map.of("root", root, "leaf", leaf);

        List<TraceStep> steps = List.of(
            step(0, "leaf", TraceStatus.HAS_ROWS),
            stepError(1, "root", TraceStatus.TIMEOUT, "query timeout after 30s")
        );

        EliminationOutcome outcome = detector.findEliminationOutcome(steps, nodes);
        assertInstanceOf(EliminationOutcome.Error.class, outcome);
        assertEquals("query timeout after 30s", ((EliminationOutcome.Error) outcome).reason());
    }

    @Test
    void outcome_isEdge_returnsChildAndParent() {
        FragmentNode leaf = node("leaf", List.of());
        FragmentNode root = node("root", List.of(leaf));
        Map<String, FragmentNode> nodes = Map.of("root", root, "leaf", leaf);

        List<TraceStep> steps = List.of(
            step(0, "leaf", TraceStatus.HAS_ROWS),
            step(1, "root", TraceStatus.NO_ROWS)
        );

        EliminationOutcome outcome = detector.findEliminationOutcome(steps, nodes);
        assertInstanceOf(EliminationOutcome.Edge.class, outcome);
        EliminationOutcome.Edge edge = (EliminationOutcome.Edge) outcome;
        assertEquals("leaf", edge.childStep().fragmentId());
        assertEquals("root", edge.parentStep().fragmentId());
    }

    @Test
    void noElimination_allHasRows() {
        FragmentNode leaf = node("leaf", List.of());
        FragmentNode root = node("root", List.of(leaf));
        Map<String, FragmentNode> nodes = Map.of("root", root, "leaf", leaf);

        List<TraceStep> steps = List.of(
            step(0, "leaf", TraceStatus.HAS_ROWS),
            step(1, "root", TraceStatus.HAS_ROWS)
        );

        assertInstanceOf(EliminationOutcome.None.class, detector.findEliminationOutcome(steps, nodes));
    }

    @Test
    void eliminationAtJoin_childHasRows_parentNoRows() {
        FragmentNode left = node("left", List.of());
        FragmentNode right = node("right", List.of());
        FragmentNode join = node("join", List.of(left, right));
        FragmentNode root = node("root", List.of(join));
        Map<String, FragmentNode> nodes = Map.of(
            "root", root, "join", join, "left", left, "right", right);

        List<TraceStep> steps = List.of(
            step(0, "left", TraceStatus.HAS_ROWS),
            step(1, "right", TraceStatus.HAS_ROWS),
            step(2, "join", TraceStatus.NO_ROWS),
            step(3, "root", TraceStatus.NO_ROWS)
        );

        EliminationOutcome outcome = detector.findEliminationOutcome(steps, nodes);
        assertInstanceOf(EliminationOutcome.Edge.class, outcome);
        assertEquals("join", ((EliminationOutcome.Edge) outcome).parentStep().fragmentId());
    }

    @Test
    void eliminationAtFilter_childHasRows_parentNoRows() {
        FragmentNode leaf = node("leaf", List.of());
        FragmentNode filtered = node("filtered", List.of(leaf));
        FragmentNode root = node("root", List.of(filtered));
        Map<String, FragmentNode> nodes = Map.of(
            "root", root, "filtered", filtered, "leaf", leaf);

        List<TraceStep> steps = List.of(
            step(0, "leaf", TraceStatus.HAS_ROWS),
            step(1, "filtered", TraceStatus.NO_ROWS),
            step(2, "root", TraceStatus.NO_ROWS)
        );

        EliminationOutcome outcome = detector.findEliminationOutcome(steps, nodes);
        assertInstanceOf(EliminationOutcome.Edge.class, outcome);
        assertEquals("filtered", ((EliminationOutcome.Edge) outcome).parentStep().fragmentId());
    }

    @Test
    void allLeavesNoRows_eliminationAtFirstLeaf() {
        FragmentNode leaf = node("leaf", List.of());
        FragmentNode root = node("root", List.of(leaf));
        Map<String, FragmentNode> nodes = Map.of("root", root, "leaf", leaf);

        List<TraceStep> steps = List.of(
            step(0, "leaf", TraceStatus.NO_ROWS),
            step(1, "root", TraceStatus.NO_ROWS)
        );

        EliminationOutcome outcome = detector.findEliminationOutcome(steps, nodes);
        assertInstanceOf(EliminationOutcome.Inconclusive.class, outcome);
    }

    @Test
    void skippedNodes_ignoredInDetection() {
        FragmentNode leaf = node("leaf", List.of());
        FragmentNode root = node("root", List.of(leaf));
        Map<String, FragmentNode> nodes = Map.of("root", root, "leaf", leaf);

        List<TraceStep> steps = List.of(
            step(0, "leaf", TraceStatus.HAS_ROWS),
            step(1, "root", TraceStatus.SKIPPED)
        );

        assertInstanceOf(EliminationOutcome.Inconclusive.class, detector.findEliminationOutcome(steps, nodes));
    }

    @Test
    void multipleEliminationPoints_returnsDeepest() {
        FragmentNode leaf = node("leaf", List.of());
        FragmentNode mid = node("mid", List.of(leaf));
        FragmentNode root = node("root", List.of(mid));
        Map<String, FragmentNode> nodes = Map.of("root", root, "mid", mid, "leaf", leaf);

        List<TraceStep> steps = List.of(
            step(0, "leaf", TraceStatus.HAS_ROWS),
            step(1, "mid", TraceStatus.NO_ROWS),
            step(2, "root", TraceStatus.NO_ROWS)
        );

        EliminationOutcome outcome = detector.findEliminationOutcome(steps, nodes);
        assertInstanceOf(EliminationOutcome.Edge.class, outcome);
        assertEquals("mid", ((EliminationOutcome.Edge) outcome).parentStep().fragmentId());
    }

    @Test
    void emptySteps_returnsNone() {
        assertInstanceOf(EliminationOutcome.Inconclusive.class, detector.findEliminationOutcome(List.of(), Map.of()));
    }
}
