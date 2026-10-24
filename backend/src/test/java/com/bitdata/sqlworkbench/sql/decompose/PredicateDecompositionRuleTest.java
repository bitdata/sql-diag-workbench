package com.bitdata.sqlworkbench.sql.decompose;

import org.apache.calcite.sql.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for PredicateDecompositionRule.
 * Covers: flat AND, nested AND, single predicate, CTE, ORDER BY wrapper.
 */
public class PredicateDecompositionRuleTest extends RuleTestFixture {

    private final PredicateDecompositionRule rule = new PredicateDecompositionRule(RuleTestDeps.testResolver());

    @Test
    void decomposeFlatAnd() throws Exception {
        // Top-level AST is binary: AND(AND(a,b), c) → 2 first-level operands.
        SqlNode node = parse(
            "SELECT id, name FROM users WHERE status = 'active' AND email IS NOT NULL AND age > 18"
        );
        List<DecomposedFragment> results = rule.apply(node, "postgresql");

        assertEquals(2, results.size(),
            "Only the first level is split: top AND has 2 operands → 2 fragments");

        for (DecomposedFragment f : results) {
            assertTrue(f.label().startsWith("Keep only:"), "Label should start with 'Keep only:'");
            assertTrue(f.executable());
        }
    }

    @Test
    void noDecomposeForSinglePredicate() throws Exception {
        SqlNode node = parse(
            "SELECT id, name FROM users WHERE status = 'active'"
        );
        List<DecomposedFragment> results = rule.apply(node, "postgresql");

        assertTrue(results.isEmpty(), "Should not decompose single predicate");
    }

    @Test
    void handlesNestedAnd() throws Exception {
        // Top-level AST: AND(AND(a,b), AND(c,d)) → 2 first-level operands.
        // The nested AND subtrees are NOT recursively split.
        SqlNode node = parse(
            "SELECT id FROM t WHERE (a = 1 AND b = 2) AND (c = 3 AND d = 4)"
        );
        List<DecomposedFragment> results = rule.apply(node, "postgresql");

        assertEquals(2, results.size(),
            "Top-level AND has 2 operands (each is an AND subtree) → 2 fragments, NOT 4");
    }

    @Test
    void maxPredicateLimitIsCurrentlyUnreachable() throws Exception {
        // 12 AND-ed predicates — the parser still produces a binary AST
        // (left-associative), so the top has 2 operands. MAX_PREDICATES = 10
        // cannot be reached via SQL the parser accepts; it is a defensive guard
        // for hypothetical flat-n operands. Documented in §4.3.
        StringBuilder sb = new StringBuilder("SELECT id FROM users WHERE ");
        for (int i = 0; i < 12; i++) {
            if (i > 0) sb.append(" AND ");
            sb.append("col").append(i).append(" = ").append(i);
        }
        SqlNode node = parse(sb.toString());
        List<DecomposedFragment> results = rule.apply(node, "postgresql");

        assertEquals(2, results.size(),
            "12 AND predicates — binary AST → 2 first-level operands → 2 fragments (MAX=10 unreachable in practice)");
    }

    @Test
    void decomposeWithOrderBy() throws Exception {
        SqlNode node = parse(
            "SELECT id, name FROM users WHERE status = 'active' AND email IS NOT NULL ORDER BY id"
        );
        List<DecomposedFragment> results = rule.apply(node, "postgresql");

        assertFalse(results.isEmpty());
        for (DecomposedFragment f : results) {
            assertTrue(f.sqlFragment().contains("ORDER BY"),
                "Should preserve ORDER BY: " + f.sqlFragment());
        }
    }

    @Test
    void decomposeCteBody() throws Exception {
        SqlNode node = parse(
            "WITH V AS ("
            + "  SELECT id, name FROM users WHERE status = 'active' AND email IS NOT NULL"
            + ") "
            + "SELECT * FROM V"
        );
        List<DecomposedFragment> results = rule.apply(node, "postgresql");

        assertFalse(results.isEmpty());
        for (DecomposedFragment f : results) {
            assertTrue(f.sqlFragment().startsWith("WITH"),
                "CTE should be preserved: " + f.sqlFragment().substring(0, 50));
        }
    }

    @Test
    void noWhereReturnsEmpty() throws Exception {
        SqlNode node = parse("SELECT id, name FROM users");
        List<DecomposedFragment> results = rule.apply(node, "postgresql");

        assertTrue(results.isEmpty());
    }

    @Test
    void noWhereWithOrderByReturnsEmpty() throws Exception {
        SqlNode node = parse("SELECT id, name FROM users ORDER BY id");
        List<DecomposedFragment> results = rule.apply(node, "postgresql");

        assertTrue(results.isEmpty());
    }

    @Test
    void mysqlDialect() throws Exception {
        SqlNode node = parseMysql(
            "SELECT id FROM users WHERE status = 'active' AND name IS NOT NULL"
        );
        List<DecomposedFragment> results = rule.apply(node, "mysql");

        assertFalse(results.isEmpty());
        assertEquals(2, results.size());
    }

    @Test
    void executableFragmentContainsValidSql() throws Exception {
        SqlNode node = parse(
            "SELECT id, name FROM users WHERE status = 'active' AND email IS NOT NULL"
        );
        List<DecomposedFragment> results = rule.apply(node, "postgresql");

        for (DecomposedFragment f : results) {
            assertNotNull(f.sqlFragment());
            assertFalse(f.sqlFragment().isEmpty());
            assertTrue(f.sqlFragment().contains("SELECT"),
                "Should be valid SQL: " + f.sqlFragment());
        }
    }

    // ---- "Only split the first level (AND or OR)" guards (docs/decomposition-principles.md §4.3) ----

    /**
     * Top-level AND: an OR subtree under a top-level AND is preserved as a single
     * predicate, not recursively decomposed. `WHERE a=1 AND (b=2 OR c=3)` → 2 fragments,
     * not 3+. The OR remains intact inside one of them.
     */
    @Test
    void topLevelAnd_orSubtreePreserved() throws Exception {
        SqlNode node = parse(
            "SELECT id FROM t WHERE a = 1 AND (b = 2 OR c = 3)"
        );
        List<DecomposedFragment> results = rule.apply(node, "postgresql");

        assertEquals(2, results.size(),
            "Top-level AND with nested OR → 2 fragments (OR kept atomic): " + results);

        boolean hasA = results.stream().anyMatch(f -> f.sqlFragment().toUpperCase().contains("A = 1"));
        boolean hasOr = results.stream().anyMatch(f -> {
            String s = f.sqlFragment().toUpperCase();
            return s.contains(" OR ") && s.contains("B = 2") && s.contains("C = 3");
        });
        assertTrue(hasA, "One fragment must keep only 'a = 1': " + results);
        assertTrue(hasOr, "One fragment must keep the entire OR subtree: " + results);
    }

    /**
     * Top-level OR is split into its operands (just like top-level AND).
     * `WHERE a=1 OR b=2` → 2 fragments.
     */
    @Test
    void topLevelOr_isSplit() throws Exception {
        SqlNode node = parse(
            "SELECT id FROM t WHERE a = 1 OR b = 2"
        );
        List<DecomposedFragment> results = rule.apply(node, "postgresql");

        assertEquals(2, results.size(),
            "Top-level OR with 2 operands → 2 fragments (got " + results.size() + "): " + results);

        boolean hasA = results.stream().anyMatch(f -> f.sqlFragment().toUpperCase().contains("A = 1")
                                                  && !f.sqlFragment().toUpperCase().matches(".*\\bOR\\b.*"));
        boolean hasB = results.stream().anyMatch(f -> f.sqlFragment().toUpperCase().contains("B = 2")
                                                  && !f.sqlFragment().toUpperCase().matches(".*\\bOR\\b.*"));
        assertTrue(hasA, "One fragment must keep only 'a = 1' (no OR): " + results);
        assertTrue(hasB, "One fragment must keep only 'b = 2' (no OR): " + results);
    }

    /**
     * Parens are purely syntactic. `WHERE (a=1 OR b=2)` is identical to
     * `WHERE a=1 OR b=2` from the rule's perspective — both yield 2 fragments
     * because the parens just wrap the top-level expression.
     */
    @Test
    void parenthesizedTopLevelOr_splitsIgnoringParens() throws Exception {
        SqlNode node = parse(
            "SELECT id FROM t WHERE (a = 1 OR b = 2)"
        );
        List<DecomposedFragment> results = rule.apply(node, "postgresql");

        assertEquals(2, results.size(),
            "WHERE (a=1 OR b=2): parens ignored, top is OR with 2 operands → 2 fragments (got "
                + results.size() + "): " + results);
    }
}