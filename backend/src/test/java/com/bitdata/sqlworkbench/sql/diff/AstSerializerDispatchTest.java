package com.bitdata.sqlworkbench.sql.diff;

import com.bitdata.sqlworkbench.fragment.FragmentIdentity;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 分派穷尽 + 兜底内容可分（spec 2026-10-01 §4.1）。
 *
 * <p>每一个断言都对应一条 recon 探针（2026-10-02）实测出的碰撞：见各用例方法里的
 * "recon:" 行。旧实现（{@code 08471dc} 之前）上最初 8 条中 <b>6 条红</b>；另 2 条
 * （{@code plainAllIsStillEquivalentToOmittedAll}、{@code unknownMarkerItselfSurvives}）
 * 本来就绿 —— 它们钉的是反向约束（ALL 与省略等价、UNKNOWN 标记本身保留），
 * 不是待修的缺陷。
 */
class AstSerializerDispatchTest {

    private static List<NodeToken> tokens(String sql) throws Exception {
        return AstSerializer.serialize(sql, "postgresql");
    }

    private static String canonical(String sql) throws Exception {
        return SqlCanonicalizer.canonicalizeSql(sql, "postgresql");
    }

    private static String id(String sql) throws Exception {
        return FragmentIdentity.computeId(canonical(sql), "postgresql");
    }

    private static String unknowns(List<NodeToken> toks) {
        return toks.stream()
            .filter(t -> "UNKNOWN".equals(t.nodeType()))
            .map(NodeToken::canonicalText)
            .collect(Collectors.joining(","));
    }

    /** 断言失败信息里回显整条 token 流用。 */
    private static String tk(List<NodeToken> toks) {
        return toks.stream()
            .map(t -> t.nodeType() + ":" + t.canonicalText())
            .collect(Collectors.joining(" | "));
    }

    @Test
    void orderByQueryIsDispatchedAndKeepsItsStructure() throws Exception {
        List<NodeToken> toks = tokens("SELECT a FROM t ORDER BY a");
        // recon: 旧实现 tokens=1（UNKNOWN@root:SqlOrderBy），整条查询坍缩。
        assertEquals("", unknowns(toks), "ORDER BY 查询不得再落兜底");
        assertTrue(toks.stream().anyMatch(t -> "ORDER_BY".equals(t.nodeType())));
        assertTrue(toks.stream().anyMatch(t -> "SELECT".equals(t.nodeType())),
            "wrapper 里的 SELECT 必须被递归出来，否则内层结构全丢");
        assertTrue(toks.stream().anyMatch(t -> "TABLE".equals(t.nodeType())));
    }

    @Test
    void limitAndOffsetAreDispatchedToo() throws Exception {
        // recon: Calcite 1.37 把 LIMIT / OFFSET 也包成 SqlOrderBy，同样坍缩。
        assertEquals("", unknowns(tokens("SELECT a FROM t LIMIT 5")));
        assertEquals("", unknowns(tokens("SELECT a FROM t OFFSET 3")));
        // 零-UNKNOWN 只证明「没落兜底」，不证明两者可分：若回归把 LIMIT 与 OFFSET
        // 路由到同一 child/path，两条都会「无 UNKNOWN」而彼此同 id。故加可分性钉子。
        assertNotEquals(id("SELECT a FROM t LIMIT 5"), id("SELECT a FROM t OFFSET 3"),
            "LIMIT 与 OFFSET 是不同子句，不得共享 id");
    }

    @Test
    void distinctOrderByQueriesDoNotCollide() throws Exception {
        // recon: 两条完全不同的查询旧实现同 id。
        assertNotEquals(
            id("SELECT a FROM t ORDER BY a"),
            id("SELECT b FROM u ORDER BY b DESC"));
    }

    @Test
    void groupByAndHavingReachTheTokenStream() throws Exception {
        List<NodeToken> toks = tokens("SELECT a FROM t GROUP BY a HAVING COUNT(*) > 1");
        // recon: 旧实现四条 token，与 `SELECT a FROM t` 完全相同 ⇒ 静默碰撞，
        // 且因为不产生 UNKNOWN，零-UNKNOWN 闸门查不出来。
        assertTrue(toks.stream().anyMatch(t -> "GROUP_BY".equals(t.nodeType())));
        assertTrue(toks.stream().anyMatch(t -> "HAVING".equals(t.nodeType())));
        assertNotEquals(id("SELECT a FROM t"), id("SELECT a FROM t GROUP BY a HAVING COUNT(*) > 1"));
        assertNotEquals(id("SELECT a FROM t GROUP BY a"), id("SELECT a FROM t GROUP BY b"));
    }

    @Test
    void distinctModifierReachesTheTokenStream() throws Exception {
        // recon: `SELECT DISTINCT a FROM t` 与 `SELECT a FROM t` 旧实现同串同 id。
        assertTrue(tokens("SELECT DISTINCT a FROM t").stream()
            .anyMatch(t -> "SELECT_MODIFIER".equals(t.nodeType())));
        assertNotEquals(id("SELECT a FROM t"), id("SELECT DISTINCT a FROM t"));
    }

    @Test
    void plainAllIsStillEquivalentToOmittedAll() throws Exception {
        // 反向约束（ADR-0005 等价换写语义）：ALL 是默认值，显式写与省略必须同身份。
        // 这条在旧实现与修复后都绿 —— 它防的是「顺手把 ALL 也做成 token」。
        assertEquals(id("SELECT a FROM t"), id("SELECT ALL a FROM t"));
    }

    @Test
    void undispatchedTypesGetContentBearingFallbackText() throws Exception {
        List<NodeToken> a = tokens("UPDATE t SET a = 1");
        List<NodeToken> b = tokens("UPDATE u SET b = 2");
        assertEquals("UNKNOWN", a.get(0).nodeType(), "UPDATE 仍未分派（本迭代不做 DML 分派）");
        assertTrue(a.get(0).canonicalText().startsWith("SqlUpdate#"),
            "兜底 token 必须带类名 + 内容摘要，实际=" + a.get(0).canonicalText());
        assertNotEquals(a.get(0).canonicalText(), b.get(0).canonicalText(),
            "recon: 旧实现两条都发 UNKNOWN@root:SqlUpdate ⇒ 同 id");
        assertNotEquals(id("UPDATE t SET a = 1"), id("UPDATE u SET b = 2"));
        assertNotEquals(id("SET search_path TO public"), id("SET search_path TO other"));
    }

    @Test
    void namedWindowClauseReachesTheTokenStream() throws Exception {
        // recon 2026-10-02：具名 WINDOW 定义旧实现完全不可见 ⇒ `WINDOW w AS (ORDER BY a)`、
        // `(ORDER BY b)`、无 WINDOW 三串同 id（4fed6b854ed49a76）。
        // 注意：SqlSelect.getWindowList() **永不为 null**（默认空 SqlNodeList），
        // 故守卫必须是 size()>0，否则每个 SELECT 都会多出一个 WINDOW token。
        List<NodeToken> toks = tokens("SELECT sum(a) OVER w FROM t WINDOW w AS (ORDER BY a)");
        assertEquals("", unknowns(toks), "WINDOW 定义体不得落兜底（walk 必须完整）");
        assertTrue(toks.stream().anyMatch(t -> "WINDOW".equals(t.nodeType())),
            "WINDOW 子句标记 token 缺失，实际=" + tk(toks));
        assertTrue(toks.stream().anyMatch(t -> "WINDOW_DEF".equals(t.nodeType())),
            "窗口定义体 token 缺失，实际=" + tk(toks));
        assertNotEquals(id("SELECT sum(a) OVER w FROM t WINDOW w AS (ORDER BY a)"),
                        id("SELECT sum(a) OVER w FROM t WINDOW w AS (ORDER BY b)"),
                        "两处排序键不同的窗口定义必须不同 id");
        assertNotEquals(id("SELECT sum(a) OVER w FROM t WINDOW w AS (ORDER BY a)"),
                        id("SELECT sum(a) OVER w FROM t"),
                        "带 WINDOW 与不带 WINDOW 的查询必须不同 id");
    }

    @Test
    void windowClauseAbsentEmitsNoWindowToken() throws Exception {
        // 反向约束：无 WINDOW 的查询不得因为 windowList 非 null 而多出 WINDOW token，
        // 否则所有既有 canonical 串都会变。
        List<NodeToken> toks = tokens("SELECT a FROM t");
        assertFalse(toks.stream().anyMatch(t -> "WINDOW".equals(t.nodeType())
            || "WINDOW_DEF".equals(t.nodeType())),
            "无 WINDOW 子句不得发 WINDOW token，实际=" + tk(toks));
    }

    @Test
    void qualifyClauseReachesTheTokenStream() throws Exception {
        // recon 2026-10-02：QUALIFY 旧实现完全不可见 ⇒ 与裸 `SELECT a FROM t` 同 id
        // （1c607e78a4c96977），两个不同的 QUALIFY 谓词之间也互相碰撞。
        List<NodeToken> toks = tokens("SELECT a FROM t QUALIFY row_number() OVER (ORDER BY a) = 1");
        assertEquals("", unknowns(toks), "QUALIFY 谓词不得落兜底（walk 必须完整）");
        assertTrue(toks.stream().anyMatch(t -> "QUALIFY".equals(t.nodeType())),
            "QUALIFY token 缺失，实际=" + tk(toks));
        assertNotEquals(id("SELECT a FROM t QUALIFY row_number() OVER (ORDER BY a) = 1"),
                        id("SELECT a FROM t QUALIFY row_number() OVER (ORDER BY b) = 1"),
                        "两处不同的 QUALIFY 谓词必须不同 id");
        assertNotEquals(id("SELECT a FROM t QUALIFY row_number() OVER (ORDER BY a) = 1"),
                        id("SELECT a FROM t"),
                        "带 QUALIFY 与不带 QUALIFY 的查询必须不同 id");
    }

    @Test
    void streamModifierReachesTheTokenStream() throws Exception {
        // recon 2026-10-02：`SELECT STREAM a FROM t` 与 `SELECT a FROM t` 旧实现同 id
        // （1c607e78a4c96977）。STREAM 是 SqlSelectKeyword 三值中最后一个未处理的
        // （DISTINCT 在 SELECT_MODIFIER 分支，ALL 是默认值、必须与省略等价）。
        List<NodeToken> toks = tokens("SELECT STREAM a FROM t");
        assertTrue(toks.stream().anyMatch(
                t -> "SELECT_MODIFIER".equals(t.nodeType()) && "STREAM".equals(t.canonicalText())),
            "STREAM 修饰 token 缺失，实际=" + tk(toks));
        assertNotEquals(id("SELECT a FROM t"), id("SELECT STREAM a FROM t"),
            "带 STREAM 与不带 STREAM 的查询必须不同 id");
        assertNotEquals(id("SELECT STREAM a FROM t"), id("SELECT DISTINCT a FROM t"),
            "STREAM 与 DISTINCT 是不同修饰，不得同 id");
    }

    @Test
    void unknownMarkerItselfSurvives() throws Exception {
        // ADR-0005：UNKNOWN 是**有意**的诚实标注，本迭代不改它的存在，只让它内容可分。
        assertTrue(tokens("UPDATE t SET a = 1").stream()
            .anyMatch(t -> "UNKNOWN".equals(t.nodeType())));
    }
}
