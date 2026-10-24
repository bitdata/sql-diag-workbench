package com.bitdata.sqlworkbench.sql.decompose;

import com.bitdata.sqlworkbench.sql.model.FragmentNode;
import com.bitdata.sqlworkbench.sql.model.FragmentType;
import com.bitdata.sqlworkbench.sql.SqlParseEngine;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.parser.SqlParseException;
import org.apache.calcite.sql.*;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.bitdata.sqlworkbench.sql.decompose.SqlNodeBuilder.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Decomposition tests using programmatically constructed ASTs.
 *
 * These tests do NOT parse SQL strings — they build SqlNodes via the
 * Calcite API directly. This isolates decomposition rule correctness
 * from parser behavior.
 */
public class DecompositionIsolationTest {

    private final ClauseStrippingRule clauseRule = new ClauseStrippingRule(RuleTestDeps.testResolver());
    private final PredicateDecompositionRule predicateRule = new PredicateDecompositionRule(RuleTestDeps.testResolver());
    private final SplitSetOpRule setOpRule = new SplitSetOpRule(RuleTestDeps.testResolver());

    // ---- ClauseStrippingRule tests (programmatic ASTs) ----

    @Test
    void stripWhereFromProgrammaticSelect() {
        SqlNode node = new SqlNodeBuilder()
            .select("id", "name")
            .from("users")
            .where(b -> b.eq("status", "active").build())
            .buildSelect();

        List<DecomposedFragment> results = clauseRule.apply(node, "postgresql");

        assertTrue(results.stream().anyMatch(f -> f.label().contains("WHERE")),
            "Should strip WHERE from programmatic AST");
        DecomposedFragment stripped = results.stream()
            .filter(f -> f.label().contains("WHERE")).findFirst().orElseThrow();
        assertFalse(stripped.sqlFragment().contains("WHERE"),
            "Stripped SQL should not contain WHERE clause");
    }

    @Test
    void stripOrderByFromProgrammaticAst() {
        SqlNode node = new SqlNodeBuilder()
            .select("id", "name")
            .from("users")
            .orderBy(b -> b.desc("created_at").build())
            .buildOrderBy();

        List<DecomposedFragment> results = clauseRule.apply(node, "postgresql");

        assertTrue(results.stream().anyMatch(f -> f.label().contains("ORDER BY")),
            "Should strip ORDER BY from programmatic AST");
    }

    @Test
    void stripLimitOffsetFromProgrammaticAst() {
        SqlNode node = new SqlNodeBuilder()
            .select("id", "name")
            .from("users")
            .orderBy(b -> b.asc("id").build())
            .limit(10)
            .offset(5)
            .buildOrderBy();

        List<DecomposedFragment> results = clauseRule.apply(node, "postgresql");

        assertTrue(results.stream().anyMatch(f -> f.label().contains("LIMIT")),
            "Should strip LIMIT/OFFSET from programmatic AST");
    }

    @Test
    void stripHavingFromProgrammaticAst() {
        SqlNode node = new SqlNodeBuilder()
            .select("name")
            .from("users")
            .groupBy("name")
            .having(b -> b.gt("count", 1).build())
            .buildSelect();

        List<DecomposedFragment> results = clauseRule.apply(node, "postgresql");

        assertTrue(results.stream().anyMatch(f -> f.label().contains("HAVING")),
            "Should strip HAVING from programmatic AST");
    }

    @Test
    void emptySelectProducesNoStrippingFragments() {
        SqlNode node = new SqlNodeBuilder()
            .select("id")
            .from("users")
            .buildSelect();

        List<DecomposedFragment> results = clauseRule.apply(node, "postgresql");

        assertTrue(results.isEmpty(),
            "Simple SELECT with no clauses should produce no fragments");
    }

    @Test
    void allStrippedFragmentsAreExecutable() {
        SqlNode node = new SqlNodeBuilder()
            .select("id", "name", "email")
            .from("users")
            .where(b -> b.and(
                b2 -> b2.eq("status", "active").build(),
                b2 -> b2.isNotNull("email").build()).build())
            .orderBy(b -> b.desc("id").build())
            .limit(20)
            .buildOrderBy();

        List<DecomposedFragment> results = clauseRule.apply(node, "postgresql");

        assertFalse(results.isEmpty());
        for (DecomposedFragment f : results) {
            assertTrue(f.executable(), "Fragment should be executable: " + f.label());
            assertNotNull(f.sqlFragment());
            assertFalse(f.sqlFragment().isBlank());
            assertTrue(f.sqlFragment().contains("SELECT"),
                "Should contain SELECT: " + f.sqlFragment());
        }
    }

    // ---- PredicateDecompositionRule tests (programmatic ASTs) ----

    @Test
    void decomposeAndPredicatesFromProgrammaticAst() {
        // SqlNodeBuilder.and(c1, c2, c3) produces AND(AND(c1,c2), c3) — binary tree.
        // Top has 2 operands → 2 fragments. The inner AND(c1,c2) is one fragment.
        SqlNode node = new SqlNodeBuilder()
            .select("id", "name")
            .from("users")
            .where(b -> b.and(
                b2 -> b2.eq("status", "active").build(),
                b2 -> b2.isNotNull("email").build(),
                b2 -> b2.gt("age", 18).build()).build())
            .buildSelect();

        List<DecomposedFragment> results = predicateRule.apply(node, "postgresql");

        assertEquals(2, results.size(),
            "Only the first level is split: top AND has 2 operands → 2 fragments");
        for (DecomposedFragment f : results) {
            assertTrue(f.label().startsWith("Keep only:"));
            assertEquals(FragmentType.PREDICATE_DECOMPOSED, f.type());
            assertTrue(f.executable());
        }
    }

    @Test
    void singlePredicateNoDecompositionFromProgrammaticAst() {
        SqlNode node = new SqlNodeBuilder()
            .select("id", "name")
            .from("users")
            .where(b -> b.eq("status", "active").build())
            .buildSelect();

        List<DecomposedFragment> results = predicateRule.apply(node, "postgresql");

        assertTrue(results.isEmpty(),
            "Single predicate should not be decomposed");
    }

    @Test
    void noWhereClauseNoDecompositionFromProgrammaticAst() {
        SqlNode node = new SqlNodeBuilder()
            .select("id", "name")
            .from("users")
            .buildSelect();

        List<DecomposedFragment> results = predicateRule.apply(node, "postgresql");

        assertTrue(results.isEmpty(),
            "SELECT without WHERE should produce no fragments");
    }

    @Test
    void decomposeWithOrderByPreservesOrderingFromProgrammaticAst() {
        SqlNode node = new SqlNodeBuilder()
            .select("id", "name")
            .from("users")
            .where(b -> b.and(
                b2 -> b2.eq("status", "active").build(),
                b2 -> b2.isNotNull("email").build()).build())
            .orderBy(b -> b.asc("id").build())
            .buildOrderBy();

        List<DecomposedFragment> results = predicateRule.apply(node, "postgresql");

        assertFalse(results.isEmpty());
        for (DecomposedFragment f : results) {
            assertTrue(f.sqlFragment().contains("ORDER BY"),
                "Should preserve ORDER BY: " + f.sqlFragment());
        }
    }

    // ---- SplitSetOpRule tests (programmatic ASTs) ----

    @Test
    void splitsUnionFromProgrammaticAst() {
        SqlNode left = new SqlNodeBuilder()
            .select("id").from("t1").buildSelect();
        SqlNode right = new SqlNodeBuilder()
            .select("id").from("t2").buildSelect();

        SqlNode node = new SqlNodeBuilder()
            .setOp("UNION", left, right)
            .buildSetOp();

        List<DecomposedFragment> results = setOpRule.apply(node, "postgresql");

        assertEquals(2, results.size());
        assertTrue(results.stream().allMatch(f -> f.type() == FragmentType.SET_SPLIT));
        assertTrue(results.stream().allMatch(f -> f.label().contains("branch")));
    }

    @Test
    void splitsIntersectFromProgrammaticAst() {
        SqlNode left = new SqlNodeBuilder()
            .select("id").from("t1").buildSelect();
        SqlNode right = new SqlNodeBuilder()
            .select("id").from("t2").buildSelect();

        SqlNode node = new SqlNodeBuilder()
            .setOp("INTERSECT", left, right)
            .buildSetOp();

        List<DecomposedFragment> results = setOpRule.apply(node, "postgresql");

        assertEquals(2, results.size());
        assertTrue(results.stream().allMatch(f -> f.type() == FragmentType.SET_SPLIT));
    }

    @Test
    void splitsExceptFromProgrammaticAst() {
        SqlNode left = new SqlNodeBuilder()
            .select("id").from("t1").buildSelect();
        SqlNode right = new SqlNodeBuilder()
            .select("id").from("t2").buildSelect();

        SqlNode node = new SqlNodeBuilder()
            .setOp("EXCEPT", left, right)
            .buildSetOp();

        List<DecomposedFragment> results = setOpRule.apply(node, "postgresql");

        assertEquals(2, results.size());
    }

    @Test
    void nonSetOpReturnsEmptyFromProgrammaticAst() {
        SqlNode node = new SqlNodeBuilder()
            .select("id").from("t1")
            .where(b -> b.eq("status", "active").build())
            .buildSelect();

        List<DecomposedFragment> results = setOpRule.apply(node, "postgresql");

        assertTrue(results.isEmpty(),
            "Regular SELECT should not be processed by set-op rule");
    }

    @Test
    void setOpBranchSqlIsStandalone() {
        SqlNode left = new SqlNodeBuilder()
            .select("id", "name").from("t1").buildSelect();
        SqlNode right = new SqlNodeBuilder()
            .select("id").from("t2").buildSelect();

        SqlNode node = new SqlNodeBuilder()
            .setOp("UNION", left, right)
            .buildSetOp();

        List<DecomposedFragment> results = setOpRule.apply(node, "postgresql");

        for (DecomposedFragment f : results) {
            assertFalse(f.sqlFragment().contains("UNION"),
                "Branch SQL should not contain UNION: " + f.sqlFragment());
            assertTrue(f.sqlFragment().contains("SELECT"),
                "Should be a valid SELECT: " + f.sqlFragment());
        }
    }

    // ---- Cross-rule: all rules accept programmatic SqlSelect ----

    @Test
    void allRulesAcceptProgrammaticSimpleSelect() {
        SqlNode node = SqlNodeBuilder.simpleSelect("id", "name");

        assertDoesNotThrow(() -> clauseRule.apply(node, "postgresql"));
        assertDoesNotThrow(() -> predicateRule.apply(node, "postgresql"));
        assertDoesNotThrow(() -> setOpRule.apply(node, "postgresql"));
    }

    @Test
    void allRulesAcceptProgrammaticSelectWithWhere() {
        SqlNode node = new SqlNodeBuilder()
            .select("id", "name", "email")
            .from("users")
            .where(b -> b.and(
                b2 -> b2.eq("status", "active").build(),
                b2 -> b2.isNotNull("email").build(),
                b2 -> b2.gt("age", 18).build()).build())
            .buildSelect();

        assertDoesNotThrow(() -> clauseRule.apply(node, "postgresql"));
        assertDoesNotThrow(() -> predicateRule.apply(node, "postgresql"));
        assertDoesNotThrow(() -> setOpRule.apply(node, "postgresql"));
    }

    // ---- stableId stability test ----

    @Test
    void stableId_sameSqlTwice_producesIdenticalIds() throws SqlParseException {
        String sql = "SELECT id, name FROM users WHERE status = 'active'";

        SqlParseEngine parseEngine = new SqlParseEngine(RuleTestDeps.testResolver());

        // First parse
        SqlNode node1 = parseEngine.parse(sql, "postgresql");
        DecompositionRuleEngine engine1 = new DecompositionRuleEngine(
            new ClauseStrippingRule(RuleTestDeps.testResolver()), new SplitSetOpRule(RuleTestDeps.testResolver()),
            new PredicateDecompositionRule(RuleTestDeps.testResolver()), new CteExpansionRule(RuleTestDeps.testResolver()),
            new JoinSplitRule(RuleTestDeps.testResolver()), new SubqueryExtractionRule(RuleTestDeps.testResolver()),
            RuleTestDeps.testResolver()
        );
        FragmentNode root1 = engine1.build(node1, "postgresql");
        List<String> ids1 = collectIds(root1);

        // Second parse (new engine instance simulates fresh request)
        SqlNode node2 = parseEngine.parse(sql, "postgresql");
        DecompositionRuleEngine engine2 = new DecompositionRuleEngine(
            new ClauseStrippingRule(RuleTestDeps.testResolver()), new SplitSetOpRule(RuleTestDeps.testResolver()),
            new PredicateDecompositionRule(RuleTestDeps.testResolver()), new CteExpansionRule(RuleTestDeps.testResolver()),
            new JoinSplitRule(RuleTestDeps.testResolver()), new SubqueryExtractionRule(RuleTestDeps.testResolver()),
            RuleTestDeps.testResolver()
        );
        FragmentNode root2 = engine2.build(node2, "postgresql");
        List<String> ids2 = collectIds(root2);

        assertEquals(ids1, ids2, "node ids must be identical across two builds of the same SQL");
    }

    private List<String> collectIds(FragmentNode node) {
        List<String> ids = new ArrayList<>();
        ids.add(node.id());
        for (FragmentNode child : node.children()) {
            ids.addAll(collectIds(child));
        }
        return ids;
    }
}
