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
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var ab = json.readTree(canon("SELECT a FROM t ORDER BY a, b")).get(2).get(1).get(2);
        var ba = json.readTree(canon("SELECT a FROM t ORDER BY b, a")).get(2).get(1).get(2);
        assertEquals(2, ab.size());
        assertEquals("[\"A\"]", ab.get(0).get(1).asText());
        assertEquals("[\"B\"]", ab.get(1).get(1).asText());
        assertEquals(ab.get(0), ba.get(1));
        assertEquals(ab.get(1), ba.get(0));
    }

    @Test
    void genuineOrPredicatesStillCommute() throws Exception {
        // 反向约束：修法不得把 OR 的交换律一起打掉。
        assertEquals(canon("SELECT a FROM t WHERE a > 0 OR b < 1"),
            canon("SELECT a FROM t WHERE b < 1 OR a > 0"),
            "OR 操作数可交换（ADR-0001/P1）");
        assertTrue(canon("SELECT a FROM t WHERE a > 0 OR b < 1").contains("CALL_OR"),
            "真实 OR 块仍应被重编号为 .OR.n");
    }

    @Test
    void genuineAndPredicatesStillCommute() throws Exception {
        // 反向约束：.AND 标记无误判站点，行为必须与修前一致。
        assertEquals(canon("SELECT a FROM t WHERE a > 0 AND b < 1"),
            canon("SELECT a FROM t WHERE b < 1 AND a > 0"));
        assertTrue(canon("SELECT a FROM t WHERE a > 0 AND b < 1").contains("CALL_AND"));
    }
}
