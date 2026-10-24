package com.bitdata.sqlworkbench.sql.diff;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 结构化降级标志（spec §4.2「而非对 canonical 串做前缀嗅探」）。 */
class SqlCanonicalizerDegradationTest {

    @Test
    void supportedSqlIsNotDegraded() throws Exception {
        var form = SqlCanonicalizer.canonicalizeSqlWithStatus("SELECT a FROM t ORDER BY a", "postgresql");
        assertFalse(form.degraded(), "ORDER BY 已分派（T1），不得报降级");
        assertTrue(form.unsupportedNodeTypes().isEmpty());
        assertEquals(SqlCanonicalizer.canonicalizeSql("SELECT a FROM t ORDER BY a", "postgresql"),
            form.canonicalSql(), "canonicalizeSql 必须与新方法同源");
    }

    @Test
    void undispatchedSqlIsDegradedWithTheOffendingTypes() throws Exception {
        var form = SqlCanonicalizer.canonicalizeSqlWithStatus("UPDATE t SET a = 1", "postgresql");
        assertTrue(form.degraded());
        assertEquals(1, form.unsupportedNodeTypes().size());
        assertTrue(form.unsupportedNodeTypes().get(0).startsWith("SqlUpdate#"),
            "报告的是兜底 token 的 canonicalText（类名 + 摘要），实际=" + form.unsupportedNodeTypes());
    }

    @Test
    void degradationIsDetectedFromTokensNotStringMatching() throws Exception {
        // 反向约束：合法 SQL 里出现 "UNKNOWN" 字面量（标识符/字符串）不得被判降级。
        var form = SqlCanonicalizer.canonicalizeSqlWithStatus(
            "SELECT unknown FROM t WHERE note = 'UNKNOWN'", "postgresql");
        assertFalse(form.degraded(),
            "判据必须来自 token 类型，不是 canonical 串里的子串匹配");
    }
}
