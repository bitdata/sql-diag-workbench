package com.bitdata.sqlworkbench.execution;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Structural pin for the getStatus publish-atomicity fix.
 * Spec: docs/superpowers/specs/2026-09-25-getstatus-atomic-publish-design.md §4 / §6.1.
 *
 * <p>Why a shape assertion instead of a behavioral one: defect (A) is a
 * microsecond-scale ordering race whose window cannot be reliably triggered in a
 * unit test, so the deterministic evidence for (A) is structural. The four
 * CANCELLED sites that used to be the only DETERMINISTICALLY observable
 * "terminal + null error" have since been fixed — the cancel() entry by Task 5
 * and the three worker checkpoints by Task 6 (all publish a reason-tagged
 * result) — so they are no longer a deterministic witness of (A) either.
 *
 * <p>Deliberate cost: this test is coupled to the implementation's shape. If the
 * publish shape is legitimately changed later, this test goes red and must be
 * updated with it — that is its job.
 */
class ExecutionEnginePublishShapeTest {

    private static Class<?> executionContextType() {
        return Arrays.stream(ExecutionEngine.class.getDeclaredClasses())
            .filter(c -> c.getSimpleName().equals("ExecutionContext"))
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "ExecutionEngine no longer declares a nested ExecutionContext class"));
    }

    @Test
    void executionContextHasNoSeparatelyAssignableStatusOrResult() {
        Set<String> fields = Arrays.stream(executionContextType().getDeclaredFields())
            .map(Field::getName)
            .collect(Collectors.toSet());

        assertFalse(fields.contains("status"),
            "ExecutionContext.status must not exist as its own field — a second volatile "
                + "field re-opens the status/result publish window (spec §4). Fields: " + fields);
        assertFalse(fields.contains("result"),
            "ExecutionContext.result must not exist as its own field — see spec §4. "
                + "Fields: " + fields);
    }

    @Test
    void executionContextPublishesThroughASingleSnapshotField() {
        Set<String> fields = Arrays.stream(executionContextType().getDeclaredFields())
            .map(Field::getName)
            .collect(Collectors.toSet());

        assertTrue(fields.contains("published"),
            "ExecutionContext must carry the single snapshot field 'published'. Fields: " + fields);
    }
}
