package com.bitdata.sqlworkbench.sql.decompose;

import com.bitdata.sqlworkbench.sql.model.FragmentType;
import org.apache.calcite.sql.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class SubqueryExtractionRuleTest extends RuleTestFixture {

    private final SubqueryExtractionRule rule = new SubqueryExtractionRule(RuleTestDeps.testResolver());

    @Test
    void extractsInSubquery() throws Exception {
        SqlNode node = parse(
            "SELECT * FROM users WHERE id IN (SELECT orders.user_id FROM orders)"
        );
        List<DecomposedFragment> results = rule.apply(node, "postgresql");

        assertFalse(results.isEmpty(), "Should extract IN subquery");
        assertEquals(FragmentType.SUBQUERY_EXTRACTED, results.get(0).type());
        assertTrue(results.get(0).label().contains("WHERE"));
        assertTrue(results.get(0).sqlFragment().contains("SELECT"),
            "Extracted SQL should be a SELECT");
    }

    @Test
    void extractsExistsSubquery() throws Exception {
        SqlNode node = parse(
            "SELECT * FROM users WHERE EXISTS (SELECT 1 FROM orders WHERE orders.user_id = users.id)"
        );
        List<DecomposedFragment> results = rule.apply(node, "postgresql");

        assertFalse(results.isEmpty());
        assertTrue(results.get(0).label().contains("WHERE"));
        // Correlated condition (users.id) should be removed from WHERE
        // SELECT stays as original (SELECT 1) per user requirement
        String sql = results.get(0).sqlFragment();
        assertFalse(sql.contains("users.id"), "Should remove correlated users.id from WHERE");
        assertTrue(sql.contains("SELECT 1"), "SELECT list should remain unchanged");
    }

    @Test
    void extractsFromClauseSubquery() throws Exception {
        SqlNode node = parse(
            "SELECT * FROM (SELECT users.id, users.name FROM users WHERE users.status = 'active') AS t"
        );
        List<DecomposedFragment> results = rule.apply(node, "postgresql");

        assertFalse(results.isEmpty(), "Should extract FROM clause subquery");
        assertTrue(results.get(0).label().contains("FROM"));
        assertTrue(results.get(0).sqlFragment().contains("SELECT"),
            "Extracted SQL should contain SELECT: " + results.get(0).sqlFragment());
    }

    @Test
    void extractsSelectListSubquery() throws Exception {
        SqlNode node = parse(
            "SELECT id, (SELECT MAX(orders.amount) FROM orders WHERE orders.user_id = users.id) FROM users"
        );
        List<DecomposedFragment> results = rule.apply(node, "postgresql");

        assertFalse(results.isEmpty(), "Should extract SELECT list scalar subquery");
        assertTrue(results.get(0).label().contains("SELECT list"));
        // Correlated condition should be removed
        String sql = results.get(0).sqlFragment();
        assertFalse(sql.contains("users.id"), "Should remove correlated users.id from WHERE");
    }

    @Test
    void noSubqueryReturnsEmpty() throws Exception {
        SqlNode node = parse("SELECT id, name FROM users");
        List<DecomposedFragment> results = rule.apply(node, "postgresql");

        assertTrue(results.isEmpty(), "Plain SELECT should have no subqueries");
    }

    @Test
    void fragmentsAreExecutable() throws Exception {
        SqlNode node = parse(
            "SELECT * FROM users WHERE id IN (SELECT orders.user_id FROM orders)"
        );
        List<DecomposedFragment> results = rule.apply(node, "postgresql");

        assertFalse(results.isEmpty());
        for (DecomposedFragment f : results) {
            assertTrue(f.executable(), f.label() + " should be executable");
            assertNotNull(f.sqlFragment());
            assertFalse(f.sqlFragment().isBlank());
        }
    }

    @Test
    void extractsCteBodySubquery() throws Exception {
        SqlNode node = parse(
            "WITH v AS (SELECT id FROM users) SELECT * FROM v WHERE id IN (SELECT orders.user_id FROM orders)"
        );
        List<DecomposedFragment> results = rule.apply(node, "postgresql");

        assertFalse(results.isEmpty(), "Should extract subquery from CTE body");
        assertTrue(results.stream().anyMatch(f -> f.label().contains("WHERE")));
    }

    @Test
    void mysqlDialect() throws Exception {
        SqlNode node = parseMysql(
            "SELECT * FROM users WHERE id IN (SELECT orders.user_id FROM orders)"
        );
        List<DecomposedFragment> results = rule.apply(node, "mysql");

        assertFalse(results.isEmpty());
    }

    // ---- P1-7: extracted subquery must not carry outer ORDER BY ----

    @Test
    void extractedSubquery_fromClause_hasNoOuterOrderBy() throws Exception {
        // Outer query's ORDER BY references outer alias 't.x' which is not
        // visible inside the extracted subquery. P1-7: extracted subquery
        // is a standalone data-source fragment — outer ORDER BY must not be
        // attached.
        SqlNode node = parse(
            "SELECT * FROM (SELECT users.id, users.name FROM users WHERE users.status = 'active') AS t "
            + "ORDER BY t.name DESC"
        );
        List<DecomposedFragment> results = rule.apply(node, "postgresql");

        assertFalse(results.isEmpty(), "Should extract FROM-clause subquery");
        for (DecomposedFragment f : results) {
            assertFalse(f.sqlFragment().contains("ORDER BY"),
                "Extracted subquery must not carry outer ORDER BY: " + f.sqlFragment());
            assertFalse(f.sqlFragment().contains("t.name"),
                "Extracted subquery must not reference outer alias: " + f.sqlFragment());
        }
    }

    @Test
    void extractedSubquery_inPredicate_hasNoOuterOrderBy() throws Exception {
        // Outer ORDER BY on outer column must not bleed into the extracted
        // IN-subquery fragment (the IN subquery itself never had an ORDER BY
        // — confirm the wrapper doesn't add one).
        SqlNode node = parse(
            "SELECT * FROM users WHERE id IN (SELECT orders.user_id FROM orders) "
            + "ORDER BY users.name DESC"
        );
        List<DecomposedFragment> results = rule.apply(node, "postgresql");

        assertFalse(results.isEmpty());
        for (DecomposedFragment f : results) {
            assertFalse(f.sqlFragment().contains("ORDER BY"),
                "Extracted IN-subquery must not carry outer ORDER BY: " + f.sqlFragment());
        }
    }
}
