package com.bitdata.sqlworkbench.sql.decompose;

import com.bitdata.sqlworkbench.sql.model.FragmentType;
import org.apache.calcite.sql.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for SplitSetOpRule.
 * Covers: UNION, UNION ALL, INTERSECT, EXCEPT, and mixed set operations.
 */
public class SplitSetOpRuleTest extends RuleTestFixture {

    private final SplitSetOpRule rule = new SplitSetOpRule(RuleTestDeps.testResolver());

    @Test
    void splitsUnion() throws Exception {
        SqlNode node = parse(
            "SELECT id FROM t1 UNION SELECT id FROM t2"
        );
        List<DecomposedFragment> results = rule.apply(node, "postgresql");

        assertFalse(results.isEmpty());
        assertEquals(2, results.size());

        assertTrue(results.stream().allMatch(f ->
            f.label().contains("branch")), "Should label branches");
        assertTrue(results.stream().allMatch(f ->
            f.type() == FragmentType.SET_SPLIT), "Should be SET_SPLIT type");
    }

    @Test
    void splitsUnionAll() throws Exception {
        SqlNode node = parse(
            "SELECT id FROM t1 UNION ALL SELECT id FROM t2 UNION ALL SELECT id FROM t3"
        );
        List<DecomposedFragment> results = rule.apply(node, "postgresql");

        assertEquals(3, results.size());
    }

    @Test
    void splitsIntersect() throws Exception {
        SqlNode node = parse(
            "SELECT id FROM t1 INTERSECT SELECT id FROM t2"
        );
        List<DecomposedFragment> results = rule.apply(node, "postgresql");

        assertFalse(results.isEmpty());
        assertEquals(2, results.size());
    }

    @Test
    void splitsExcept() throws Exception {
        SqlNode node = parse(
            "SELECT id FROM t1 EXCEPT SELECT id FROM t2"
        );
        List<DecomposedFragment> results = rule.apply(node, "postgresql");

        assertFalse(results.isEmpty());
        assertEquals(2, results.size());
    }

    @Test
    void preservesOrderBy() throws Exception {
        // P1-8 fix: set-op-level ORDER BY applies to the UNION result, not to
        // individual branches. Carrying it into each branch produces invalid
        // SQL when the order key references an alias from a different branch.
        SqlNode node = parse(
            "SELECT id, name AS x FROM t1 UNION SELECT id, code AS x FROM t2 ORDER BY x"
        );
        List<DecomposedFragment> results = rule.apply(node, "postgresql");

        assertFalse(results.isEmpty());
        for (DecomposedFragment f : results) {
            assertFalse(f.sqlFragment().contains("ORDER BY"),
                "Branch fragment must not carry set-op ORDER BY (key may reference other branch's alias): "
                    + f.sqlFragment());
        }
    }

    @Test
    void branchSplit_doesNotCarrySetOpOrderBy() throws Exception {
        // P1-8 regression: ORDER BY key 't2.id' references a column only
        // present in the second branch. Carrying it into the first branch
        // produces invalid SQL.
        SqlNode node = parse(
            "SELECT id FROM t1 UNION SELECT id FROM t2 ORDER BY t2.id"
        );
        List<DecomposedFragment> results = rule.apply(node, "postgresql");

        assertEquals(2, results.size());
        for (DecomposedFragment f : results) {
            assertFalse(f.sqlFragment().contains("ORDER BY"),
                "Branch fragment must not carry set-op ORDER BY: " + f.sqlFragment());
            assertFalse(f.sqlFragment().contains("t2.id"),
                "Branch SQL must not reference set-op ORDER BY key: " + f.sqlFragment());
        }
    }

    @Test
    void nonSetOpReturnsEmpty() throws Exception {
        SqlNode node = parse("SELECT id FROM t1 WHERE status = 'active'");
        List<DecomposedFragment> results = rule.apply(node, "postgresql");

        assertTrue(results.isEmpty());
    }

    @Test
    void simpleSelectReturnsEmpty() throws Exception {
        SqlNode node = parse("SELECT id, name FROM users");
        List<DecomposedFragment> results = rule.apply(node, "postgresql");

        assertTrue(results.isEmpty());
    }

    @Test
    void fragmentsAreExecutable() throws Exception {
        SqlNode node = parse(
            "SELECT id FROM t1 UNION SELECT id FROM t2"
        );
        List<DecomposedFragment> results = rule.apply(node, "postgresql");

        for (DecomposedFragment f : results) {
            assertTrue(f.executable(), "Fragment should be executable");
            assertNotNull(f.sqlFragment());
            assertFalse(f.sqlFragment().isEmpty());
        }
    }

    @Test
    void branchSqlIsStandalone() throws Exception {
        SqlNode node = parse(
            "SELECT id, name FROM t1 UNION SELECT id FROM t2"
        );
        List<DecomposedFragment> results = rule.apply(node, "postgresql");

        for (DecomposedFragment f : results) {
            String sql = f.sqlFragment();
            assertFalse(sql.contains("UNION"), "Branch SQL should be standalone: " + sql);
            assertTrue(sql.contains("SELECT"), "Should be valid SELECT: " + sql);
        }
    }

    @Test
    void mysqlDialect() throws Exception {
        SqlNode node = parseMysql(
            "SELECT id FROM t1 UNION SELECT id FROM t2"
        );
        List<DecomposedFragment> results = rule.apply(node, "mysql");

        assertFalse(results.isEmpty());
        assertEquals(2, results.size());
    }
}