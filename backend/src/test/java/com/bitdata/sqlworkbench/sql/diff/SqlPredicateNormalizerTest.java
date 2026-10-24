package com.bitdata.sqlworkbench.sql.diff;

import com.bitdata.sqlworkbench.fragment.FragmentIdentity;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Spec §3.5 / §8.4 — P1 谓词等价规则：
 *   1. 方向归一：a&lt;b ≡ b&gt;a, a&lt;=b ≡ b&gt;=a
 *   2. De Morgan：NOT(a OR b) ≡ NOT a AND NOT b, NOT(a AND b) ≡ NOT a OR NOT b
 *   3. &lt;&gt; ↔ !=
 *
 * <p>通过 ChangeAttribution.attributeChange 端到端验证：等价形态的
 * attributeChange(before, after, d) 必须返回空集（Δ = ∅）。
 */
class SqlPredicateNormalizerTest {

    @Nested
    class DirectionEquivalence {
        @Test
        void aLessThanB_equalsBGreaterThanA() throws Exception {
            // Both "WHERE a < 5" and "WHERE 5 > a" should produce Δ = ∅ when
            // diffed against each other (P1 canonicalizes to the same form).
            assertDeltaEmpty(
                "SELECT * FROM t WHERE a < 5",
                "SELECT * FROM t WHERE 5 > a");
        }

        @Test
        void aLessThanOrEqualB_equalsBGreaterThanOrEqualA() throws Exception {
            assertDeltaEmpty(
                "SELECT * FROM t WHERE a <= 5",
                "SELECT * FROM t WHERE 5 >= a");
        }

        @Test
        void sameDirection_producesEmptyDelta() throws Exception {
            // Sanity check: P0+P1 must not break the existing identity case.
            assertDeltaEmpty(
                "SELECT * FROM t WHERE a < 5",
                "SELECT * FROM t WHERE a < 5");
        }
    }

    @Nested
    class DeMorgan {
        @Test
        void notAOrB_equalsNotAAndNotB() throws Exception {
            // NOT(a OR b) ≡ NOT a AND NOT b → Δ = ∅
            assertDeltaEmpty(
                "SELECT * FROM t WHERE NOT (a > 0 OR b < 5)",
                "SELECT * FROM t WHERE NOT a > 0 AND NOT b < 5");
        }

        @Test
        void notAAndB_equalsNotAOrNotB() throws Exception {
            // NOT(a AND b) ≡ NOT a OR NOT b → Δ = ∅
            assertDeltaEmpty(
                "SELECT * FROM t WHERE NOT (a > 0 AND b < 5)",
                "SELECT * FROM t WHERE NOT a > 0 OR NOT b < 5");
        }

        @Test
        void doubleNegation_collapsed() throws Exception {
            // NOT NOT a > 0 ≡ a > 0 → Δ = ∅
            assertDeltaEmpty(
                "SELECT * FROM t WHERE NOT NOT a > 0",
                "SELECT * FROM t WHERE a > 0");
        }
    }

    @Nested
    class NotEqualEquivalence {
        /**
         * Calcite's default SQL conformance disallows `!=`, so we can only
         * exercise the `<>` form. The P1 rewrite logic in
         * {@code rewriteNotEqual} is in place (always emits SqlStdOperatorTable.NOT_EQUALS
         * with the unparse form `&lt;&gt;` → `a&lt;&gt;b`), and the canonical
         * form is stable across re-runs.
         */
        @Test
        void notEqual_canonicalFormIsStable() throws Exception {
            String c1 = SqlCanonicalizer.canonicalizeSql(
                "SELECT * FROM t WHERE a <> b", "postgresql");
            String c2 = SqlCanonicalizer.canonicalizeSql(
                "SELECT * FROM t WHERE a <> b", "postgresql");
            assertEquals(c1, c2);
        }
    }

    @Nested
    class CanonicalizeSql {
        @Test
        void canonicalizeSql_equivalentPredicatesProduceSameString() throws Exception {
            String c1 = SqlCanonicalizer.canonicalizeSql(
                "SELECT * FROM t WHERE a < 5", "postgresql");
            String c2 = SqlCanonicalizer.canonicalizeSql(
                "SELECT * FROM t WHERE 5 > a", "postgresql");
            assertEquals(c1, c2);
        }

        @Test
        void canonicalizeSql_deMorganProducesSameString() throws Exception {
            String c1 = SqlCanonicalizer.canonicalizeSql(
                "SELECT * FROM t WHERE NOT (a > 0 OR b < 5)", "postgresql");
            String c2 = SqlCanonicalizer.canonicalizeSql(
                "SELECT * FROM t WHERE NOT a > 0 AND NOT b < 5", "postgresql");
            assertEquals(c1, c2);
        }
    }

    /**
     * P1 归一必须到达 {@code AstSerializer.walk} 会递归进入的每一个包装器。
     *
     * <p>2026-10-02 探针：改写在包装器**内部**时，旧 {@code normalizeAst} 只下降三层
     * （{@code SqlNodeList} / {@code SqlSelect} 的 where+having / {@code SqlBasicCall}），
     * 其余落终态 {@code return node} —— 于是「等价改写却算出不同 fragment id」。
     * 每条用例都同时钉住裸对照（未包装的同型改写）与反退化守卫：把包装器自身的内容
     * 换掉时必须换 id，否则等值断言可能因两侧一起退化成同一条坏串而假绿。
     */
    @Nested
    class WrapperDescentEquivalence {

        @Test
        void orderByRootWherePredicateIsNormalized() throws Exception {
            assertSameId("SELECT a FROM t WHERE b < a",
                         "SELECT a FROM t WHERE a > b");                       // 裸对照
            assertSameId("SELECT a FROM t WHERE b < a ORDER BY a",
                         "SELECT a FROM t WHERE a > b ORDER BY a");            // (fix) SqlOrderBy
            assertIdChangesWhenWrapperChanges("SELECT a FROM t WHERE b < a ORDER BY a",
                                              "SELECT a FROM t WHERE b < a ORDER BY b");
        }

        @Test
        void limitRootWherePredicateIsNormalized() throws Exception {
            assertSameId("SELECT a FROM t WHERE b < a LIMIT 5",
                         "SELECT a FROM t WHERE a > b LIMIT 5");               // (fix) SqlOrderBy
            assertIdChangesWhenWrapperChanges("SELECT a FROM t WHERE b < a LIMIT 5",
                                              "SELECT a FROM t WHERE b < a LIMIT 6");
        }

        @Test
        void orderByItemsAreNormalized() throws Exception {
            assertSameId("SELECT a FROM t ORDER BY b < a",
                         "SELECT a FROM t ORDER BY a > b");                    // (fix) SqlOrderBy.orderList
            assertIdChangesWhenWrapperChanges("SELECT a FROM t ORDER BY b < a",
                                              "SELECT a FROM t ORDER BY a");
        }

        @Test
        void joinConditionIsNormalized() throws Exception {
            assertSameId("SELECT x.a FROM t x JOIN u y ON x.b < y.c",
                         "SELECT x.a FROM t x JOIN u y ON y.c > x.b");         // (fix) SqlJoin
            assertIdChangesWhenWrapperChanges("SELECT x.a FROM t x JOIN u y ON x.b < y.c",
                                              "SELECT x.a FROM t x JOIN v y ON x.b < y.c");
        }

        @Test
        void derivedTablePredicateIsNormalized() throws Exception {
            assertSameId("SELECT x.a FROM (SELECT a FROM t WHERE b < a) x",
                         "SELECT x.a FROM (SELECT a FROM t WHERE a > b) x");   // (fix) SqlSelect.from
            assertIdChangesWhenWrapperChanges(
                "SELECT x.a FROM (SELECT a FROM t WHERE b < a) x",
                "SELECT x.a FROM (SELECT a FROM u WHERE b < a) x");
        }

        @Test
        void groupByItemsAreNormalized() throws Exception {
            assertSameId("SELECT count(*) FROM t GROUP BY b < a",
                         "SELECT count(*) FROM t GROUP BY a > b");             // (fix) SqlSelect.group
            assertIdChangesWhenWrapperChanges("SELECT count(*) FROM t GROUP BY b < a",
                                              "SELECT count(*) FROM t GROUP BY a");
        }

        @Test
        void havingPredicateIsNormalizedAndParticipates() throws Exception {
            // HAVING 是唯一需要两向断言的子句，缺哪一条都不成立：
            //  (a) 等价塌缩 —— 两个方向写法同 id。**这条在 08471dc 之前本就绿**：
            //      那时 HAVING 根本不发 token，两串一起塌缩成裸 `SELECT a FROM t`，
            //      等值是为错的理由通过的（也因此它单独不能证明 HAVING 被归一）。
            //  (b) 参与性 —— HAVING 在/不在必须不同 id。**这条在 08471dc 之前是红的**
            //      （probe：旧实现 `GROUP BY a HAVING b < a` 与 `GROUP BY a` 同 id）。
            //      它是唯一能区分「HAVING 不可见」与「HAVING 可见且等价」的断言，
            //      故 (a) 若因 token 再次消失而假绿，(b) 会立刻红。
            assertSameId("SELECT a FROM t GROUP BY a HAVING b < a",
                         "SELECT a FROM t GROUP BY a HAVING a > b");           // (a) 等价塌缩
            assertIdChangesWhenWrapperChanges("SELECT a FROM t GROUP BY a HAVING b < a",
                                              "SELECT a FROM t GROUP BY a");       // (b) 参与性
            assertIdChangesWhenWrapperChanges("SELECT a FROM t GROUP BY a HAVING b < a",
                                              "SELECT a FROM t GROUP BY a HAVING b < c");
        }

        @Test
        void selectListItemsAreNormalized() throws Exception {
            assertSameId("SELECT b < a FROM t",
                         "SELECT a > b FROM t");                               // (fix) SqlSelect.selectList
            assertIdChangesWhenWrapperChanges("SELECT b < a FROM t",
                                              "SELECT b < a FROM u");
        }

        @Test
        void cteBodyPredicateIsNormalized() throws Exception {
            assertSameId("WITH c AS (SELECT a FROM t WHERE b < a) SELECT a FROM c",
                         "WITH c AS (SELECT a FROM t WHERE a > b) SELECT a FROM c");  // (fix) SqlWithItem
            assertIdChangesWhenWrapperChanges(
                "WITH c AS (SELECT a FROM t WHERE b < a) SELECT a FROM c",
                "WITH c AS (SELECT a FROM t WHERE b < a) SELECT a FROM d");
        }

        @Test
        void cteMainBodyPredicateIsNormalized() throws Exception {
            assertSameId("WITH c AS (SELECT a FROM t) SELECT a FROM c WHERE b < a",
                         "WITH c AS (SELECT a FROM t) SELECT a FROM c WHERE a > b");  // (fix) SqlWith.body
        }

        @Test
        void qualifyPredicateIsNormalized() throws Exception {
            // QUALIFY 谓词本体直到本轮 R-2 才进入 token 流；本用例钉住它同时被归一。
            assertSameId("SELECT a FROM t QUALIFY b < a",
                         "SELECT a FROM t QUALIFY a > b");                     // (fix) SqlSelect.qualify
            assertIdChangesWhenWrapperChanges("SELECT a FROM t QUALIFY b < a",
                                              "SELECT a FROM t");
        }

        @Test
        void windowOrderListPredicatesAreNormalized() throws Exception {
            assertSameId("SELECT a FROM t WHERE b < a",
                         "SELECT a FROM t WHERE a > b");                       // 裸对照
            assertSameId("SELECT sum(a) OVER (ORDER BY b < a) FROM t",
                         "SELECT sum(a) OVER (ORDER BY a > b) FROM t");        // (fix) SqlWindow.orderList
            assertIdChangesWhenWrapperChanges("SELECT sum(a) OVER (ORDER BY b < a) FROM t",
                                              "SELECT sum(a) OVER (ORDER BY b) FROM t");
        }

        /**
         * 与 ORDER BY 腿拆成两个方法：同一方法里 ORDER BY 断言在前，若它先红，
         * JUnit 会在该腿之前中止本方法，partition 腿就从未被单独观察过
         * （2026-10-02 评审指出的「掩码腿」）。拆开后每腿各自独立可红。
         */
        @Test
        void windowPartitionListPredicatesAreNormalized() throws Exception {
            assertSameId("SELECT a FROM t WHERE b < a",
                         "SELECT a FROM t WHERE a > b");                       // 裸对照
            assertSameId("SELECT sum(a) OVER (PARTITION BY b < a) FROM t",
                         "SELECT sum(a) OVER (PARTITION BY a > b) FROM t");    // (fix) SqlWindow.partitionList
            assertIdChangesWhenWrapperChanges("SELECT sum(a) OVER (PARTITION BY b < a) FROM t",
                                              "SELECT sum(a) OVER (PARTITION BY b) FROM t");
        }

        @Test
        void namedWindowDefinitionPredicatesAreNormalized() throws Exception {
            assertSameId("SELECT a FROM t WHERE b < a",
                         "SELECT a FROM t WHERE a > b");                       // 裸对照
            assertSameId("SELECT sum(a) OVER w FROM t WINDOW w AS (ORDER BY b < a)",
                         "SELECT sum(a) OVER w FROM t WINDOW w AS (ORDER BY a > b)");  // (fix) SqlWindow
            assertIdChangesWhenWrapperChanges(
                "SELECT sum(a) OVER w FROM t WINDOW w AS (ORDER BY b < a)",
                "SELECT sum(a) OVER w FROM t WINDOW w AS (ORDER BY b)");
        }

        @Test
        void basicCallOperandsAreNormalized() throws Exception {
            // 旧实现已覆盖（SqlBasicCall 逐个操作数下降）——钉住不变式，防回归。
            assertSameId("SELECT a FROM t WHERE b < a AND c < d",
                         "SELECT a FROM t WHERE a > b AND d > c");
        }
    }

    private static String fragmentId(String sql) throws Exception {
        return FragmentIdentity.computeId(
            SqlCanonicalizer.canonicalizeSql(sql, "postgresql"), "postgresql");
    }

    /** 等价改写 ⇒ 同 fragment id（ADR-0001/0005）。 */
    private static void assertSameId(String a, String b) throws Exception {
        assertEquals(fragmentId(a), fragmentId(b),
            "等价形态必须算出同一 fragment id：\n  " + a + "\n  " + b);
    }

    /**
     * 反退化守卫：换掉包装器自身的内容必须换 id。若两侧都退化成同一条坏串（例如整段
     * 被折叠成同一个兜底 token），等值断言会假绿 —— 这条守卫把那种情况钉红。
     */
    private static void assertIdChangesWhenWrapperChanges(String a, String different) throws Exception {
        assertNotEquals(fragmentId(a), fragmentId(different),
            "包装器内容变化必须改变 id，否则等值断言退化无意义：\n  " + a + "\n  " + different);
    }

    private static void assertDeltaEmpty(String before, String after) throws Exception {
        var ops = ChangeAttribution.attributeChange(before, after, "postgresql");
        assertEquals(0, ops.size(),
            "Expected Δ = ∅ for equivalent forms, got " + ops + " for\n  before: " + before + "\n  after:  " + after);
    }

    @Nested
    class DialectAwareness {
        @Test
        void mysqlBackTickIdentifierNormalizes() throws Exception {
            assertNotNull(
                SqlPredicateNormalizer.normalize(
                    "SELECT `order` FROM `t` WHERE `x` = 1", "mysql"),
                "mysql 反引号 SQL 应能 normalize，dialect 参数须生效");
        }

        @Test
        void postgresqlDoubleQuoteIdentifierNormalizes() throws Exception {
            assertNotNull(
                SqlPredicateNormalizer.normalize(
                    "SELECT \"order\" FROM \"t\" WHERE \"x\" = 1", "postgresql"));
        }
    }
}
