package com.bitdata.sqlworkbench.sql.diff;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** P2-13：规模守卫 + 限内行为不变。 */
class AstLcsDifferTest {

    /** NodeToken record order: (nodeType, canonicalText, path). */
    private static NodeToken tok(String type, String path, String text) {
        return new NodeToken(type, text, path);
    }

    private static List<NodeToken> seq(String prefix, int n) {
        List<NodeToken> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(tok("IDENTIFIER", "p" + i, prefix + i));
        }
        return out;
    }

    @Test
    void smallInputsUseLcsAndDetectSingleMod() {
        List<NodeToken> before = List.of(
            tok("IDENTIFIER", "p0", "a"),
            tok("LITERAL", "p1", "1"));
        List<NodeToken> after = List.of(
            tok("IDENTIFIER", "p0", "a"),
            tok("LITERAL", "p1", "2"));

        Set<ChangeOp> ops = AstLcsDiffer.diff(before, after);

        assertEquals(1, ops.size(), "同 path 的 DEL+ADD 应合并为 MOD，got: " + ops);
        assertEquals(ChangeOp.Kind.MOD, ops.iterator().next().kind());
    }

    @Test
    void identicalSequencesProduceNoOps() {
        List<NodeToken> s = seq("x", 50);
        assertTrue(AstLcsDiffer.diff(s, s).isEmpty());
    }

    @Test
    void oversizedInputFallsBackToPathSetDiffWithoutOom() {
        // 3000 x 3000 = 9_000_000 > MAX_LCS_CELLS(4_000_000)
        List<NodeToken> before = seq("a", 3000);
        List<NodeToken> after = new ArrayList<>(seq("a", 3000));
        after.set(0, tok("IDENTIFIER", "p0", "CHANGED"));

        Set<ChangeOp> ops = AstLcsDiffer.diff(before, after);

        assertFalse(ops.isEmpty(), "降级路径仍须产出 path 对齐的差异");
        assertTrue(ops.stream().anyMatch(op -> op.path().equals("p0")),
            "变化的 p0 应被检出，got: " + ops);
    }

    @Test
    void thresholdIsExposedForCallers() {
        assertEquals(4_000_000, AstLcsDiffer.MAX_LCS_CELLS);
    }

    // ---- P2-13 degrade flag observability (CLAUDE.md「降级必须可观测」) ----

    /** Small input: diffResult must NOT flag degradation, method = LCS. */
    @Test
    void diffResult_smallInput_notDegradedUsesLcs() {
        List<NodeToken> before = List.of(
            tok("IDENTIFIER", "p0", "a"),
            tok("LITERAL", "p1", "1"));
        List<NodeToken> after = List.of(
            tok("IDENTIFIER", "p0", "a"),
            tok("LITERAL", "p1", "2"));

        DiffResult result = AstLcsDiffer.diffResult(before, after);

        assertFalse(result.degraded(), "small input must use LCS, not degrade");
        assertEquals("LCS", result.method());
        assertEquals(1, result.ops().size(), "single MOD expected, got: " + result.ops());
        assertEquals(ChangeOp.Kind.MOD, result.ops().iterator().next().kind());
    }

    /** Oversized input: diffResult MUST flag degradation, method = PATH_SET. */
    @Test
    void diffResult_oversizedInput_degradedUsesPathSet() {
        // 3000 x 3000 = 9_000_000 > MAX_LCS_CELLS(4_000_000)
        List<NodeToken> before = seq("a", 3000);
        List<NodeToken> after = new ArrayList<>(seq("a", 3000));
        after.set(0, tok("IDENTIFIER", "p0", "CHANGED"));

        DiffResult result = AstLcsDiffer.diffResult(before, after);

        assertTrue(result.degraded(), "oversized input must flag degradation");
        assertEquals("PATH_SET", result.method());
        assertFalse(result.ops().isEmpty(), "降级路径仍须产出 path 对齐的差异");
        assertTrue(result.ops().stream().anyMatch(op -> op.path().equals("p0")),
            "变化的 p0 应被检出，got: " + result.ops());
    }

    /** diff() stays a faithful delegate of diffResult().ops() — no behavior drift. */
    @Test
    void diff_delegatesToDiffResultOps() {
        List<NodeToken> before = seq("a", 3000);
        List<NodeToken> after = new ArrayList<>(seq("a", 3000));
        after.set(0, tok("IDENTIFIER", "p0", "CHANGED"));

        Set<ChangeOp> fromDiff = AstLcsDiffer.diff(before, after);
        Set<ChangeOp> fromResult = AstLcsDiffer.diffResult(before, after).ops();

        assertEquals(fromDiff, fromResult,
            "diff() must equal diffResult(...).ops()");
    }
}
