package com.bitdata.sqlworkbench.sql.diff;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ADR-0005承重墙原型：变更归因 = 序列化 AST + LCS + 规范化（P0）。
 *
 * <p>核心验收场景：用户诊断场景 A→B、A→C、C→D，必须满足：
 * <ul>
 *   <li>ΔAC ⊆ ΔAB（"A→C 是 A→B 的子集"）</li>
 *   <li>ΔCD ⊆ ΔAB</li>
 *   <li>ΔDB = ΔAB \ ΔAD（"D→B 虚拟边 = A→B 减去 A→D"）</li>
 * </ul>
 */
class ChangeAttributionTest {

    // ── 诊断场景固定 SQL（用户 2026-06-19 给出）────────────────────────
    private static final String A =
        "SELECT * FROM t WHERE a>0 AND b<20 AND c<5";
    private static final String B =
        "SELECT * FROM t WHERE a>0 AND b<=20 AND c>=5 AND d>1";
    private static final String C =
        "SELECT * FROM t WHERE a>0 AND b<=20 AND c<5";
    private static final String D =
        "SELECT * FROM t WHERE a>0 AND b<=20 AND c>=5";

    // ── 1. NodeToken 身份 ─────────────────────────────────────────────

    @Nested
    class NodeTokenIdentity {
        @Test
        void sameContent_isEqual() {
            NodeToken x = new NodeToken("WHERE", "a>0", "root.WHERE");
            NodeToken y = new NodeToken("WHERE", "a>0", "root.WHERE");
            assertEquals(x, y);
            assertEquals(x.hashCode(), y.hashCode());
        }

        @Test
        void differentCanonicalText_notEqual() {
            NodeToken x = new NodeToken("PREDICATE", "b<20", "p.0");
            NodeToken y = new NodeToken("PREDICATE", "b<=20", "p.0");
            assertNotEquals(x, y);
        }

        @Test
        void differentNodeType_notEqual() {
            NodeToken x = new NodeToken("WHERE", "a>0", "p.0");
            NodeToken y = new NodeToken("JOIN", "a>0", "p.0");
            assertNotEquals(x, y);
        }
    }

    // ── 2. AstSerializer 基础 ─────────────────────────────────────────

    @Nested
    class AstSerializerBasic {
        @Test
        void simpleSelect_emitsSelectAndLiteral() throws Exception {
            List<NodeToken> seq = AstSerializer.serialize(
                "SELECT 1", "postgresql");
            // 至少包含 SELECT 节点和一个 1 字面量
            assertTrue(seq.stream().anyMatch(t -> "SELECT".equals(t.nodeType())),
                "应包含 SELECT 节点: " + seq);
            assertTrue(seq.stream().anyMatch(t -> "1".equals(t.canonicalText())),
                "应包含字面量 1: " + seq);
        }

        @Test
        void selectWithWhere_emitsWhereAndPredicate() throws Exception {
            List<NodeToken> seq = AstSerializer.serialize(
                "SELECT * FROM t WHERE a>0", "postgresql");
            assertTrue(seq.stream().anyMatch(t -> "WHERE".equals(t.nodeType())),
                "应包含 WHERE 节点: " + seq);
            assertTrue(seq.stream().anyMatch(t -> "a>0".equals(t.canonicalText())),
                "应包含谓词 a>0: " + seq);
        }
    }

    // ── 3. SqlCanonicalizer (P0) — AND/OR 换序等价 ─────────────────

    @Nested
    class P0Canonicalization {
        @Test
        void andChildReorder_isCanonical() throws Exception {
            String x = "SELECT * FROM t WHERE a>0 AND b<20";
            String y = "SELECT * FROM t WHERE b<20 AND a>0";
            List<NodeToken> sx = AstSerializer.serialize(x, "postgresql");
            List<NodeToken> sy = AstSerializer.serialize(y, "postgresql");
            List<NodeToken> cx = SqlCanonicalizer.canonicalize(sx);
            List<NodeToken> cy = SqlCanonicalizer.canonicalize(sy);
            assertEquals(cx, cy,
                "AND 换序后规范化序列应相等\n  cx=" + cx + "\n  cy=" + cy);
        }

        @Test
        void canonicalize_isIdempotent() throws Exception {
            String x = "SELECT * FROM t WHERE a>0 AND b<20";
            List<NodeToken> seq = AstSerializer.serialize(x, "postgresql");
            List<NodeToken> once = SqlCanonicalizer.canonicalize(seq);
            List<NodeToken> twice = SqlCanonicalizer.canonicalize(once);
            assertEquals(once, twice, "规范化必须幂等");
        }

        @Test
        void caseInsensitive_identifierNormalization() throws Exception {
            String x = "SELECT * FROM T WHERE A>0";
            String y = "select * from t where a>0";
            List<NodeToken> sx = AstSerializer.serialize(x, "postgresql");
            List<NodeToken> sy = AstSerializer.serialize(y, "postgresql");
            // Calcite 默认大小写不敏感（PG 模式下），序列化结果应已统一
            assertEquals(
                SqlCanonicalizer.canonicalize(sx),
                SqlCanonicalizer.canonicalize(sy),
                "大小写差异规范化后应相等");
        }
    }

    // ── 4. AstLcsDiffer 基础 ─────────────────────────────────────────

    @Nested
    class LcsDifferBasic {
        @Test
        void identicalSequences_emptyDiff() {
            List<NodeToken> s = List.of(
                new NodeToken("SELECT", "*", "root"),
                new NodeToken("FROM", "t", "root.FROM"));
            Set<ChangeOp> ops = AstLcsDiffer.diff(s, s);
            assertTrue(ops.isEmpty(), "相同序列应无变更: " + ops);
        }

        @Test
        void addedNode_emitsAddOp() {
            List<NodeToken> before = List.of(
                new NodeToken("SELECT", "*", "root"));
            List<NodeToken> after = List.of(
                new NodeToken("SELECT", "*", "root"),
                new NodeToken("PREDICATE", "a>0", "root.WHERE"));
            Set<ChangeOp> ops = AstLcsDiffer.diff(before, after);
            assertEquals(1, ops.size(), "应恰好 1 个 ADD: " + ops);
            ChangeOp op = ops.iterator().next();
            assertEquals(ChangeOp.Kind.ADD, op.kind());
            assertEquals("PREDICATE", op.nodeType());
            assertEquals("a>0", op.newText());
        }

        @Test
        void modifiedValue_emitsModOp() {
            List<NodeToken> before = List.of(
                new NodeToken("SELECT", "*", "root"),
                new NodeToken("PREDICATE", "b<20", "p.0"));
            List<NodeToken> after = List.of(
                new NodeToken("SELECT", "*", "root"),
                new NodeToken("PREDICATE", "b<=20", "p.0"));
            Set<ChangeOp> ops = AstLcsDiffer.diff(before, after);
            assertEquals(1, ops.size(), "应恰好 1 个 MOD: " + ops);
            ChangeOp op = ops.iterator().next();
            assertEquals(ChangeOp.Kind.MOD, op.kind());
            assertEquals("b<20", op.oldText());
            assertEquals("b<=20", op.newText());
        }
    }

    // ── 5. ChangeAttribution 端到端 — 诊断场景 A/B/C/D ──────────────

    @Nested
    class DiagnosisScenario {
        @Test
        void deltaAC_isSubsetOfDeltaAB() throws Exception {
            Set<ChangeOp> dAC = ChangeAttribution.attributeChange(A, C, "postgresql");
            Set<ChangeOp> dAB = ChangeAttribution.attributeChange(A, B, "postgresql");
            assertTrue(containsAll(dAB, dAC),
                "ΔAC ⊆ ΔAB 应成立\n  ΔAC=" + dAC + "\n  ΔAB=" + dAB);
        }

        @Test
        void deltaCD_isSubsetOfDeltaAB() throws Exception {
            Set<ChangeOp> dCD = ChangeAttribution.attributeChange(C, D, "postgresql");
            Set<ChangeOp> dAB = ChangeAttribution.attributeChange(A, B, "postgresql");
            assertTrue(containsAll(dAB, dCD),
                "ΔCD ⊆ ΔAB 应成立\n  ΔCD=" + dCD + "\n  ΔAB=" + dAB);
        }

        @Test
        void deltaDB_equalsDeltaABMinusDeltaAD() throws Exception {
            Set<ChangeOp> dAB = ChangeAttribution.attributeChange(A, B, "postgresql");
            Set<ChangeOp> dAD = ChangeAttribution.attributeChange(A, D, "postgresql");
            Set<ChangeOp> dDB = ChangeAttribution.attributeChange(D, B, "postgresql");
            // ΔDB 应该等于 ΔAB \ ΔAD（即"没在 D 里、但 B 里有的"）
            Set<ChangeOp> expected = subtract(dAB, dAD);
            assertEquals(expected, dDB,
                "ΔDB 应等于 ΔAB \\ ΔAD\n  ΔAB=" + dAB + "\n  ΔAD=" + dAD
                    + "\n  ΔDB=" + dDB + "\n  期望=" + expected);
        }

        @Test
        void andReorder_yieldsEmptyDelta() throws Exception {
            String a1 = "SELECT * FROM t WHERE a>0 AND b<20";
            String a2 = "SELECT * FROM t WHERE b<20 AND a>0";
            Set<ChangeOp> ops = ChangeAttribution.attributeChange(a1, a2, "postgresql");
            assertTrue(ops.isEmpty(),
                "AND 换序应被规范化吞掉，Δ 应为空: " + ops);
        }

        @Test
        void predicateValueChange_isSingleMod() throws Exception {
            String before = "SELECT * FROM t WHERE b<20";
            String after = "SELECT * FROM t WHERE b<=20";
            Set<ChangeOp> ops = ChangeAttribution.attributeChange(before, after, "postgresql");
            assertEquals(1, ops.size(),
                "单谓词值修改应恰好 1 个 MOD: " + ops);
            ChangeOp op = ops.iterator().next();
            assertEquals(ChangeOp.Kind.MOD, op.kind());
            assertEquals("b<20", op.oldText());
            assertEquals("b<=20", op.newText());
        }

        @Test
        void noChange_yieldsEmptyDelta() throws Exception {
            Set<ChangeOp> ops = ChangeAttribution.attributeChange(A, A, "postgresql");
            assertTrue(ops.isEmpty(), "无变更应为空: " + ops);
        }
    }

    // ── 工具方法 ─────────────────────────────────────────────────────

    private static boolean containsAll(Set<ChangeOp> super_, Set<ChangeOp> sub) {
        for (ChangeOp op : sub) {
            if (!super_.contains(op)) return false;
        }
        return true;
    }

    private static Set<ChangeOp> subtract(Set<ChangeOp> a, Set<ChangeOp> b) {
        Set<ChangeOp> result = new java.util.HashSet<>(a);
        result.removeAll(b);
        return result;
    }
}