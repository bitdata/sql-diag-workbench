package com.bitdata.sqlworkbench.sql.decompose;

import org.apache.calcite.sql.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ClauseStrippingRule.
 * Covers: simple SELECT, SELECT with ORDER BY/LIMIT, WHERE, HAVING, and CTE.
 */
public class ClauseStrippingRuleTest extends RuleTestFixture {

    private final ClauseStrippingRule rule = new ClauseStrippingRule(RuleTestDeps.testResolver());

    @Test
    void stripsOrderBy() throws Exception {
        SqlNode node = parse(
            "SELECT id, name FROM users ORDER BY created_at DESC"
        );
        List<DecomposedFragment> results = rule.apply(node, "postgresql");

        assertTrue(results.stream()
            .anyMatch(f -> f.label().contains("ORDER BY")),
            "Should produce ORDER BY removal fragment");
    }

    @Test
    void stripsLimitOffset() throws Exception {
        SqlNode node = parse(
            "SELECT id, name FROM users ORDER BY id LIMIT 10 OFFSET 5"
        );
        List<DecomposedFragment> results = rule.apply(node, "postgresql");

        assertTrue(results.stream()
            .anyMatch(f -> f.label().contains("LIMIT")),
            "Should produce LIMIT/OFFSET removal fragment");
    }

    @Test
    void stripsWhere() throws Exception {
        SqlNode node = parse(
            "SELECT id, name FROM users WHERE status = 'active'"
        );
        List<DecomposedFragment> results = rule.apply(node, "postgresql");

        assertTrue(results.stream()
            .anyMatch(f -> f.label().contains("WHERE")),
            "Should produce WHERE removal fragment");
    }

    @Test
    void stripsHaving() throws Exception {
        SqlNode node = parse(
            "SELECT name, count(*) FROM users GROUP BY name HAVING count(*) > 1"
        );
        List<DecomposedFragment> results = rule.apply(node, "postgresql");

        assertTrue(results.stream()
            .anyMatch(f -> f.label().contains("HAVING")),
            "Should produce HAVING removal fragment");
    }

    @Test
    void stripsMultipleClauses() throws Exception {
        SqlNode node = parse(
            "SELECT id, name FROM users WHERE status = 'active' ORDER BY id LIMIT 10"
        );
        List<DecomposedFragment> results = rule.apply(node, "postgresql");

        assertTrue(results.size() >= 2, "Should produce at least 2 fragments");
    }

    @Test
    void handlesEmptySelect() throws Exception {
        SqlNode node = parse("SELECT id FROM users");
        List<DecomposedFragment> results = rule.apply(node, "postgresql");

        assertNotNull(results);
    }

    @Test
    void stripsWhereFromCte() throws Exception {
        SqlNode node = parse(
            "WITH V AS (SELECT id, name FROM users WHERE status = 'active') "
            + "SELECT * FROM V"
        );
        List<DecomposedFragment> results = rule.apply(node, "postgresql");

        assertTrue(results.stream()
            .anyMatch(f -> f.label().contains("WHERE")),
            "Should strip WHERE from CTE");
    }

    @Test
    void stripsHavingFromCte() throws Exception {
        SqlNode node = parse(
            "WITH V AS (SELECT name, count(*) FROM users GROUP BY name HAVING count(*) > 1) "
            + "SELECT * FROM V"
        );
        List<DecomposedFragment> results = rule.apply(node, "postgresql");

        assertTrue(results.stream()
            .anyMatch(f -> f.label().contains("HAVING")),
            "Should strip HAVING from CTE");
    }

    @Test
    void fragmentsAreExecutable() throws Exception {
        SqlNode node = parse(
            "SELECT id, name FROM users WHERE status = 'active' AND email IS NOT NULL"
        );
        List<DecomposedFragment> results = rule.apply(node, "postgresql");

        for (DecomposedFragment f : results) {
            assertTrue(f.executable(), "Fragment should be executable: " + f.label());
            assertNotNull(f.sqlFragment());
            assertFalse(f.sqlFragment().isEmpty());
        }
    }

    @Test
    void mysqlDialect() throws Exception {
        SqlNode node = parseMysql(
            "SELECT id, name FROM users WHERE status = 'active'"
        );
        List<DecomposedFragment> results = rule.apply(node, "mysql");

        assertTrue(results.stream()
            .anyMatch(f -> f.label().contains("WHERE")),
            "Should work with MySQL dialect");
    }

    // ---- Builder-based tests (parser-free) ----

    @Test
    void stripsWhereFromBuiltAst() {
        List<DecomposedFragment> results = rule.apply(builtSelectWithWhere, "postgresql");

        assertTrue(results.stream()
            .anyMatch(f -> f.label().contains("WHERE")),
            "Should strip WHERE from programmatic AST");
    }

    @Test
    void stripsOrderByFromBuiltAst() {
        List<DecomposedFragment> results = rule.apply(builtSelectWithOrderBy, "postgresql");

        assertTrue(results.stream()
            .anyMatch(f -> f.label().contains("ORDER BY")),
            "Should strip ORDER BY from programmatic AST");
    }

    @Test
    void stripsLimitFromBuiltAst() {
        List<DecomposedFragment> results = rule.apply(builtSelectWithLimit, "postgresql");

        assertTrue(results.stream()
            .anyMatch(f -> f.label().contains("LIMIT")),
            "Should strip LIMIT from programmatic AST");
    }

    @Test
    void builtSimpleSelectProducesNoFragments() {
        List<DecomposedFragment> results = rule.apply(builtSimpleSelect, "postgresql");

        assertTrue(results.isEmpty(),
            "Simple SELECT from builder should produce no fragments");
    }

    @Test
    void builtAstFragmentsAreExecutable() {
        List<DecomposedFragment> results = rule.apply(builtSelectWithWhere, "postgresql");

        for (DecomposedFragment f : results) {
            assertTrue(f.executable(), "Fragment should be executable: " + f.label());
            assertNotNull(f.sqlFragment());
            assertFalse(f.sqlFragment().isBlank());
        }
    }

    // ---- P1-6: CTE strip correctness under duplicate-body collision ----

    /**
     * P1-6 regression: two CTEs with identical bodies. The old implementation
     * spliced the stripped body back with global {@code String.replace}, which
     * rewrote <em>both</em> CTEs' identical bodies for each fragment — so the
     * "Remove WHERE from CTE V1" fragment also stripped V2, violating the
     * "faithful, don't alter other elements" principle.
     *
     * <p>The AST-rebuild fix produces one fragment per CTE that changes only
     * its target CTE, leaving the sibling's WHERE intact. Assert: two distinct
     * WHERE-strip fragments, each keeping exactly one CTE's WHERE.
     */
    @Test
    void cteStrip_repeatedBody_eachFragmentKeepsSiblingWhere() throws Exception {
        String input =
            "WITH V1 AS (SELECT id FROM users WHERE status = 'active'), "
            +     "V2 AS (SELECT id FROM users WHERE status = 'active') "
            + "SELECT * FROM V1 JOIN V2 ON V1.id = V2.id";

        SqlNode node = parse(input);
        List<DecomposedFragment> results = rule.apply(node, "postgresql");

        List<DecomposedFragment> whereStrips = results.stream()
            .filter(f -> f.label().contains("WHERE"))
            .toList();
        assertEquals(2, whereStrips.size(),
            "Expected one WHERE-strip per CTE (2 CTEs → 2 fragments). Got: "
                + results.stream().map(DecomposedFragment::label).toList());

        // Each fragment must keep exactly one WHERE (the sibling's), so the
        // two fragments are distinct and neither equals the input (which had 2).
        long whereCount1 = countOccurrences(whereStrips.get(0).sqlFragment(), "WHERE");
        long whereCount2 = countOccurrences(whereStrips.get(1).sqlFragment(), "WHERE");
        assertEquals(1, whereCount1,
            "V1-strip fragment must keep V2's WHERE (only one WHERE). Got: " + whereStrips.get(0).sqlFragment());
        assertEquals(1, whereCount2,
            "V2-strip fragment must keep V1's WHERE (only one WHERE). Got: " + whereStrips.get(1).sqlFragment());
        assertNotEquals(whereStrips.get(0).sqlFragment(), whereStrips.get(1).sqlFragment(),
            "The two strip fragments must differ (each strips a different CTE).");
        for (DecomposedFragment f : whereStrips) {
            assertNotEquals(input, f.sqlFragment(),
                "Strip fragment must not be byte-identical to user input");
        }
    }

    private static long countOccurrences(String haystack, String needle) {
        long count = 0;
        for (int idx = 0; (idx = haystack.indexOf(needle, idx)) >= 0; idx += needle.length()) {
            count++;
        }
        return count;
    }
}