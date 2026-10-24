package com.bitdata.sqlworkbench.sql.diff;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 规则 #1 语料闸门（spec §4.1 修法 1 / §8）。
 *
 * <p>搜索面 = 产品 SQL 语料：e2e 夹具（add-back-and-edit / parse-decompose-execute 的
 * SAMPLE_SQL）+ 形态扫（CTE / 子查询 / ORDER BY / LIMIT / OFFSET / UNION / GROUP BY /
 * HAVING / DISTINCT / JOIN / window / CASE / VALUES）。每条都经 2026-10-02 探针定型。
 *
 * <p>两条断言缺一不可：
 * <ol>
 *   <li>SELECT 族语料 token 流**零 UNKNOWN** —— 钉住「该走分派的走了兜底」；</li>
 *   <li>语料**两两 canonical 不同** —— 钉住「静默不产生 token 的子句」，
 *       这类缺口零-UNKNOWN 查不出来（recon：GROUP BY / DISTINCT 旧实现无 UNKNOWN 却碰撞）。</li>
 * </ol>
 * 语料里不允许出现语义等价的成对条目（等价换写必须落在同一身份上，ADR-0005）——
 * 例如 `SELECT a FROM t` 与 `SELECT ALL a FROM t` 刻意**不**成对。
 *
 * <p>断言 2 只是单向的：「不许碰撞」一个退化成**原样回显 SQL** 的 canonicalizer 也能
 * 全对通过。故另有 {@link #equivalentRewritesShareTheSameCanonicalForm()} 补反向
 * （等价改写必须塌缩成同一 canonical），两向合起来才说明 canonical 是结构化的。
 */
class AstSerializerCorpusGateTest {

    /** 语料。新增条目时：只加**语义上互不相同**的 SQL，且 SELECT 族不得令第 1 条断言变红。 */
    private static final List<String> SELECT_CORPUS = List.of(
        // ── e2e 夹具（逐字取自 e2e/*.spec.ts 的 SAMPLE_SQL，表名不同故非等价）──
        "WITH regional AS (SELECT c.region, SUM(o.amount) AS total FROM orders o "
            + "JOIN customers c ON o.customer_id = c.id GROUP BY c.region) "
            + "SELECT region, total FROM regional ORDER BY region",
        "WITH regional AS (SELECT c.region, SUM(o.amount) AS total FROM sqldiag_it_orders o "
            + "JOIN sqldiag_it_customers c ON o.customer_id = c.id GROUP BY c.region) "
            + "SELECT region, total FROM regional ORDER BY region",
        // ── 形态扫 ──
        "SELECT a FROM t",
        "SELECT DISTINCT a FROM t",
        "SELECT a FROM t WHERE a > 0",
        "SELECT a FROM t WHERE a > 0 AND b < 1",
        "SELECT a FROM t WHERE a > 0 OR b < 1",
        "SELECT a FROM t GROUP BY a",
        "SELECT a FROM t GROUP BY b",
        "SELECT a FROM t GROUP BY a HAVING COUNT(*) > 1",
        "SELECT a FROM t ORDER BY a",
        "SELECT a FROM t ORDER BY a DESC NULLS LAST",
        "SELECT a FROM t LIMIT 5",
        "SELECT a FROM t OFFSET 3",
        "SELECT a FROM t JOIN u ON t.id = u.id",
        "SELECT a FROM (SELECT a FROM t) s",
        "SELECT a FROM (SELECT a FROM t ORDER BY a) s",
        "SELECT a FROM t WHERE a IN (SELECT b FROM u)",
        "SELECT a FROM t WHERE EXISTS (SELECT 1 FROM u WHERE u.id = t.id)",
        "(SELECT a FROM t) UNION (SELECT b FROM u)",
        "SELECT a FROM t UNION ALL SELECT b FROM u",
        "SELECT a FROM t INTERSECT SELECT b FROM u",
        "SELECT a, ROW_NUMBER() OVER (PARTITION BY b ORDER BY c) AS rn FROM t",
        "SELECT a, ROW_NUMBER() OVER (PARTITION BY d ORDER BY e) AS rn FROM t",
        // ── R-10：T1 新分派、原语料未覆盖的形态（每条都是 T1 修复点的回归钉）──
        "SELECT a, ROW_NUMBER() OVER (ORDER BY b) AS rn FROM t",
        "SELECT a, ROW_NUMBER() OVER (ORDER BY c) AS rn FROM t",
        "SELECT a FROM t WINDOW w AS (ORDER BY a)",
        "SELECT a FROM t WINDOW w AS (ORDER BY b)",
        "SELECT a, ROW_NUMBER() OVER w AS rn FROM t WINDOW w AS (ORDER BY a)",
        "SELECT STREAM a FROM t",
        "SELECT a FROM t QUALIFY ROW_NUMBER() OVER (ORDER BY c) = 1",
        "SELECT CASE WHEN a > 0 THEN 'p' ELSE 'n' END AS s FROM t",
        "VALUES (1, 2)",
        // ── T3b：多键 ORDER BY 的键序是语义（旧判据把 .ORDER_LIST 误判为 .OR 块）──
        "SELECT a FROM t ORDER BY a, b",
        "SELECT a FROM t ORDER BY b, a"
    );

    /** 产品解析器接受、但本迭代**不做**分派 → 仍应落兜底的形态（spec §3.2 边界）。 */
    private static final List<String> UNDISPATCHED_CORPUS = List.of(
        "UPDATE t SET a = 1",
        "UPDATE u SET b = 2",
        "INSERT INTO t (a) VALUES (1)",
        "SET search_path TO public",
        "EXPLAIN PLAN FOR SELECT a FROM t"
    );

    /**
     * 已被 T1 用例确立的**等价改写**对（{@code a > b} ≡ {@code b < a} 族 + ALL 省略族）。
     *
     * <p>实证锚点（逐对与 T1 测试同字面，不是本方法新造的等价主张）：
     * {@code SqlPredicateNormalizerTest$WrapperDescentEquivalence} 的
     * {@code assertSameId(...)} 各行，以及
     * {@code AstSerializerDispatchTest.plainAllIsStillEquivalentToOmittedAll}。
     *
     * <p>**故意不进** {@link #SELECT_CORPUS}：它们语义等价，进了语料会让断言 2
     * 在正确实现上变红。等价性在这里是**被断言的对象**，不是语料的去重条件。
     */
    private static final List<String[]> EQUIVALENT_REWRITES = List.of(
        new String[]{"SELECT a FROM t WHERE b < a", "SELECT a FROM t WHERE a > b"},
        new String[]{"SELECT a FROM t WHERE b < a ORDER BY a", "SELECT a FROM t WHERE a > b ORDER BY a"},
        new String[]{"SELECT a FROM t WHERE b < a LIMIT 5", "SELECT a FROM t WHERE a > b LIMIT 5"},
        new String[]{"SELECT a FROM t ORDER BY b < a", "SELECT a FROM t ORDER BY a > b"},
        new String[]{"SELECT x.a FROM t x JOIN u y ON x.b < y.c", "SELECT x.a FROM t x JOIN u y ON y.c > x.b"},
        new String[]{"SELECT x.a FROM (SELECT a FROM t WHERE b < a) x", "SELECT x.a FROM (SELECT a FROM t WHERE a > b) x"},
        new String[]{"SELECT count(*) FROM t GROUP BY b < a", "SELECT count(*) FROM t GROUP BY a > b"},
        new String[]{"SELECT a FROM t GROUP BY a HAVING b < a", "SELECT a FROM t GROUP BY a HAVING a > b"},
        new String[]{"SELECT b < a FROM t", "SELECT a > b FROM t"},
        new String[]{"WITH c AS (SELECT a FROM t WHERE b < a) SELECT a FROM c",
            "WITH c AS (SELECT a FROM t WHERE a > b) SELECT a FROM c"},
        new String[]{"WITH c AS (SELECT a FROM t) SELECT a FROM c WHERE b < a",
            "WITH c AS (SELECT a FROM t) SELECT a FROM c WHERE a > b"},
        new String[]{"SELECT a FROM t QUALIFY b < a", "SELECT a FROM t QUALIFY a > b"},
        new String[]{"SELECT sum(a) OVER (ORDER BY b < a) FROM t", "SELECT sum(a) OVER (ORDER BY a > b) FROM t"},
        new String[]{"SELECT sum(a) OVER (PARTITION BY b < a) FROM t", "SELECT sum(a) OVER (PARTITION BY a > b) FROM t"},
        new String[]{"SELECT sum(a) OVER w FROM t WINDOW w AS (ORDER BY b < a)",
            "SELECT sum(a) OVER w FROM t WINDOW w AS (ORDER BY a > b)"},
        new String[]{"SELECT a FROM t WHERE b < a AND c < d", "SELECT a FROM t WHERE a > b AND d > c"},
        new String[]{"SELECT a FROM t", "SELECT ALL a FROM t"}
    );

    private static String canonical(String sql) throws Exception {
        return SqlCanonicalizer.canonicalizeSql(sql, "postgresql");
    }

    @Test
    void selectCorpusHasNoUnknownTokens() throws Exception {
        List<String> offenders = new ArrayList<>();
        for (String sql : SELECT_CORPUS) {
            String unknowns = AstSerializer.serialize(sql, "postgresql").stream()
                .filter(t -> "UNKNOWN".equals(t.nodeType()))
                .map(t -> t.canonicalText() + "@" + t.path())
                .collect(Collectors.joining(","));
            if (!unknowns.isEmpty()) offenders.add(sql + " → " + unknowns);
        }
        assertTrue(offenders.isEmpty(),
            "SELECT 族语料不得再有 UNKNOWN（该走分派的走了兜底）：\n" + String.join("\n", offenders));
    }

    @Test
    void semanticallyDistinctCorpusEntriesGetDistinctCanonicalForms() throws Exception {
        // 每条只算一次：双层循环里现算等于每条重算 N-1 次。
        // 数字按写作时点计：本注释写于 f212da8，当时语料 33 条 ⇒ 528 对 ≈ 1056 次
        // parse+normalize+serialize；语料定稿 35 条 ⇒ 595 对（末 2 条由 dd6f592 追加）。
        List<String> canonicalForms = new ArrayList<>(SELECT_CORPUS.size());
        for (String sql : SELECT_CORPUS) canonicalForms.add(canonical(sql));

        List<String> collisions = new ArrayList<>();
        for (int i = 0; i < SELECT_CORPUS.size(); i++) {
            for (int j = i + 1; j < SELECT_CORPUS.size(); j++) {
                if (canonicalForms.get(i).equals(canonicalForms.get(j))) {
                    collisions.add(SELECT_CORPUS.get(i) + "  ≡  " + SELECT_CORPUS.get(j));
                }
            }
        }
        assertTrue(collisions.isEmpty(),
            "语义上不同的语料算出同一身份（静默丢失子句）：\n" + String.join("\n", collisions));
    }

    /**
     * 断言 2 的反向：**等价改写必须塌缩成同一 canonical**。
     *
     * <p>没有这一条，一个「原样回显 SQL」的退化 canonicalizer 能让断言 2 全对通过
     * ——「不碰撞」只证明没撞，不证明 canonical 是结构化的。两条互为守卫：
     * 退化回显 → 本方法红；退化常量 → 断言 2 红。
     */
    @Test
    void equivalentRewritesShareTheSameCanonicalForm() throws Exception {
        for (String[] pair : EQUIVALENT_REWRITES) {
            assertEquals(canonical(pair[0]), canonical(pair[1]),
                "等价改写必须塌缩成同一 canonical（ADR-0005）：\n  "
                    + pair[0] + "\n  " + pair[1]);
        }
    }

    @Test
    void undispatchedCorpusStaysDistinguishableButFlagged() throws Exception {
        List<String> fallbackTexts = new ArrayList<>();
        for (String sql : UNDISPATCHED_CORPUS) {
            List<NodeToken> toks = AstSerializer.serialize(sql, "postgresql");
            List<NodeToken> unknown = toks.stream()
                .filter(t -> "UNKNOWN".equals(t.nodeType()))
                .collect(Collectors.toList());
            assertEquals(1, unknown.size(),
                "未分派形态仍应发一个 UNKNOWN token（诚实标注保留）：" + sql);
            assertTrue(unknown.get(0).canonicalText().contains("#"),
                "兜底 token 必须带内容摘要（类名#摘要），实际=" + unknown.get(0).canonicalText() + "：" + sql);
            fallbackTexts.add(unknown.get(0).canonicalText());
        }
        // 内容可分性的**承重**断言：语料里 `UPDATE t/u` 同类不同内容，旧实现兜底只有
        // 类名 `SqlUpdate` ⇒ 两条文本相同。摘要若不参与，这里立刻红。
        assertEquals(fallbackTexts.size(), new java.util.HashSet<>(fallbackTexts).size(),
            "兜底 token 必须内容可分（同型不同内容不得同文本）：" + fallbackTexts);

        List<String> ids = new ArrayList<>();
        for (String sql : UNDISPATCHED_CORPUS) {
            ids.add(com.bitdata.sqlworkbench.fragment.FragmentIdentity.computeId(canonical(sql), "postgresql"));
        }
        assertEquals(ids.size(), new java.util.HashSet<>(ids).size(),
            "同型不同内容的未分派 SQL 必须身份可分"
                + "（recon：旧实现兜底只带类名，`UPDATE t SET a = 1` 与 `UPDATE u SET b = 2` 同文本 ⇒ 碰撞）");
    }
}
