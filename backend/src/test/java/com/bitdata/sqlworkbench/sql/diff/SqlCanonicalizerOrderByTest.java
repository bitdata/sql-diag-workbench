package com.bitdata.sqlworkbench.sql.diff;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 可交换块判据的路径段边界（规则 #1 站点扫描的唯一命中处）。
 *
 * <p>回归背景：判据用 {@code path().contains(".OR")} 子串匹配，而 ORDER BY 项的路径是
 * {@code …ORDER_LIST}，以 {@code .OR} 开头 ⇒ 键序被当成可交换项排序并重编号，
 * {@code ORDER BY a, b} 与 {@code ORDER BY b, a} 同身份（ADR-0001 假等价）。
 */
class SqlCanonicalizerOrderByTest {

    private static String canon(String sql) throws Exception {
        return SqlCanonicalizer.canonicalizeSql(sql, "postgresql");
    }

    @Test
    void multiKeyOrderByKeyOrderIsSemantic() throws Exception {
        // 旧实现：两条 canonical 串相同 ⇒ 红。
        assertNotEquals(canon("SELECT a FROM t ORDER BY a, b"),
            canon("SELECT a FROM t ORDER BY b, a"),
            "多键 ORDER BY 的键序是语义，不得被判等价");
        assertNotEquals(canon("SELECT a FROM t ORDER BY a, b, c"),
            canon("SELECT a FROM t ORDER BY c, b, a"),
            "三键同样");
    }

    @Test
    void orderByItemsKeepDocumentOrderAndTheirOwnPathNamespace() throws Exception {
        String c = canon("SELECT a FROM t ORDER BY a, b");
        assertFalse(c.contains("@.OR."),
            "ORDER BY 项不得被重编号进 OR 命名空间，实际=" + c);
        assertTrue(c.contains("ORDER_LIST"), "ORDER BY 项应保留自己的路径段，实际=" + c);
        // 必须定位到 ORDER BY 项本身：全串里第一个 ":A" 是 SELECT_LIST 项，
        // 用它比较会在**旧实现**上也成立（旧串 …SELECT_LIST:A … IDENT@.OR.0:A IDENT@.OR.1:B）。
        String ab = canon("SELECT a FROM t ORDER BY a, b");
        assertTrue(ab.indexOf("ORDER_LIST:A") < ab.indexOf("ORDER_LIST:B"),
            "键序必须保持文档序（定位 ORDER BY 项，非首个 :A），实际=" + ab);

        String ba = canon("SELECT a FROM t ORDER BY b, a");
        assertTrue(ba.indexOf("ORDER_LIST:B") < ba.indexOf("ORDER_LIST:A"),
            "反序同样必须保持文档序，实际=" + ba);
    }

    @Test
    void genuineOrPredicatesStillCommute() throws Exception {
        // 反向约束：修法不得把 OR 的交换律一起打掉。
        assertEquals(canon("SELECT a FROM t WHERE a > 0 OR b < 1"),
            canon("SELECT a FROM t WHERE b < 1 OR a > 0"),
            "OR 操作数可交换（ADR-0001/P1）");
        assertTrue(canon("SELECT a FROM t WHERE a > 0 OR b < 1").contains(".OR."),
            "真实 OR 块仍应被重编号为 .OR.n");
    }

    @Test
    void genuineAndPredicatesStillCommute() throws Exception {
        // 反向约束：.AND 标记无误判站点，行为必须与修前一致。
        assertEquals(canon("SELECT a FROM t WHERE a > 0 AND b < 1"),
            canon("SELECT a FROM t WHERE b < 1 AND a > 0"));
        assertTrue(canon("SELECT a FROM t WHERE a > 0 AND b < 1").contains(".AND."));
    }
}
