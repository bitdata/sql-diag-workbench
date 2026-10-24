package com.bitdata.sqlworkbench.sql.decompose;

import com.bitdata.sqlworkbench.sql.DialectProfile;
import com.bitdata.sqlworkbench.sql.model.JoinInfo;
import org.apache.calcite.sql.SqlNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link JoinInfoExtractor}: the read-only helper that powers
 * the "Add JOIN" composition feature. Mirrors the shape of
 * {@link JoinSplitRule} tests so semantics stay consistent.
 */
class JoinInfoExtractorTest extends RuleTestFixture {

    private final DialectProfile profile = RuleTestDeps.testResolver().getProfile("postgresql");

    @Test
    void extractsLeavesFromInnerJoin() throws Exception {
        SqlNode node = parse(
            "SELECT * FROM users INNER JOIN orders ON users.id = orders.user_id"
        );
        List<JoinInfo> joins = JoinInfoExtractor.extractJoins(node, profile);

        // Two leaves: users (anchor, joinType=INNER) and orders (added via INNER).
        // Calcite's parser preserves the original casing ("USERS", "ORDERS") under
        // caseSensitive config — assertions use equalsIgnoreCase to be robust.
        assertEquals(2, joins.size());
        assertTrue(joins.get(0).tableName().equalsIgnoreCase("users"),
            "Expected 'users', got " + joins.get(0).tableName());
        assertTrue(joins.get(1).tableName().equalsIgnoreCase("orders"),
            "Expected 'orders', got " + joins.get(1).tableName());
        assertEquals("INNER", joins.get(1).joinType().name());
    }

    @Test
    void extractsLeavesFromThreeTableJoin() throws Exception {
        SqlNode node = parse(
            "SELECT * FROM a INNER JOIN b ON a.id = b.a_id INNER JOIN c ON b.id = c.b_id"
        );
        List<JoinInfo> joins = JoinInfoExtractor.extractJoins(node, profile);

        // 3 leaves: a (anchor), b (INNER), c (INNER)
        assertEquals(3, joins.size());
        assertTrue(joins.get(0).tableName().equalsIgnoreCase("a"));
        assertTrue(joins.get(1).tableName().equalsIgnoreCase("b"));
        assertTrue(joins.get(2).tableName().equalsIgnoreCase("c"));
    }

    @Test
    void emptyForNonJoinSelect() throws Exception {
        SqlNode node = parse("SELECT * FROM users");
        assertTrue(JoinInfoExtractor.extractJoins(node, profile).isEmpty());
    }

    @Test
    void nullSafeOnNullInput() {
        assertTrue(JoinInfoExtractor.extractJoins(null, profile).isEmpty());
    }

    @Test
    void diffReturnsAddedJoin() throws Exception {
        // Parent: 3 tables (a, b, c). Child: 2 tables (a, b).
        // diff = one join (c).
        SqlNode parentNode = parse(
            "SELECT * FROM a INNER JOIN b ON a.id = b.a_id INNER JOIN c ON b.id = c.b_id"
        );
        SqlNode childNode = parse(
            "SELECT * FROM a INNER JOIN b ON a.id = b.a_id"
        );

        List<JoinInfo> parentJoins = JoinInfoExtractor.extractJoins(parentNode, profile);
        List<JoinInfo> childJoins = JoinInfoExtractor.extractJoins(childNode, profile);
        List<JoinInfo> diff = JoinInfoExtractor.diffJoins(parentJoins, childJoins);

        assertEquals(1, diff.size(), "Expected exactly one added JOIN (c)");
        assertTrue(diff.get(0).tableName().equalsIgnoreCase("c"),
            "Expected 'c', got " + diff.get(0).tableName());
        assertEquals(2, diff.get(0).position(), "c is the 3rd leaf, position=2");
    }

    @Test
    void diffIsEmptyWhenEqual() throws Exception {
        SqlNode parentNode = parse("SELECT * FROM a INNER JOIN b ON a.id = b.a_id");
        SqlNode childNode = parse("SELECT * FROM a INNER JOIN b ON a.id = b.a_id");

        List<JoinInfo> diff = JoinInfoExtractor.diffJoins(
            JoinInfoExtractor.extractJoins(parentNode, profile),
            JoinInfoExtractor.extractJoins(childNode, profile)
        );
        assertTrue(diff.isEmpty());
    }

    @Test
    void diffEmptyParentReturnsEmpty() {
        assertTrue(JoinInfoExtractor.diffJoins(List.of(), List.of()).isEmpty());
        assertTrue(JoinInfoExtractor.diffJoins(null, List.of()).isEmpty());
    }

    @Test
    void identityKeyIgnoresPosition() {
        // Position is NOT part of the identity key; self-joins are distinguished
        // by tableAlias, not by position.
        JoinInfo a = new JoinInfo("orders", "o", org.apache.calcite.sql.JoinType.INNER, "u.id = o.user_id", 0);
        JoinInfo b = new JoinInfo("orders", "o", org.apache.calcite.sql.JoinType.INNER, "u.id = o.user_id", 1);
        assertEquals(a.identityKey(), b.identityKey());
    }

    @Test
    void identityKeyIsStableForSameJoin() {
        JoinInfo a = new JoinInfo("orders", "o", org.apache.calcite.sql.JoinType.INNER, "u.id = o.user_id", 0);
        JoinInfo b = new JoinInfo("orders", "o", org.apache.calcite.sql.JoinType.INNER, "u.id = o.user_id", 0);
        assertEquals(a.identityKey(), b.identityKey());
    }

    @Test
    void diff_removingMiddleLeaf_returnsOnlyThatJoin() {
        // Parent A JOIN B JOIN C；child 删中间 B。C 共享但 position 2→1 ——
        // identityKey 必须忽略 position，diff 只返回 B。
        // 注：本文件不 import JoinType，沿用全限定名（与 identityKeyDisambiguatesPosition 一致）。
        org.apache.calcite.sql.JoinType inner = org.apache.calcite.sql.JoinType.INNER;
        JoinInfo a = new JoinInfo("a", "a", inner, "a.id = b.a_id", 0);
        JoinInfo b = new JoinInfo("b", "b", inner, "b.id = c.b_id", 1);
        JoinInfo c = new JoinInfo("c", "c", inner, "c.id = a.c_id", 2);
        JoinInfo aChild = new JoinInfo("a", "a", inner, "a.id = b.a_id", 0);
        JoinInfo cChild = new JoinInfo("c", "c", inner, "c.id = a.c_id", 1);
        List<JoinInfo> diff = JoinInfoExtractor.diffJoins(
            List.of(a, b, c), List.of(aChild, cChild));
        assertEquals(1, diff.size());
        assertTrue(diff.get(0).tableName().equalsIgnoreCase("b"));
    }
}
