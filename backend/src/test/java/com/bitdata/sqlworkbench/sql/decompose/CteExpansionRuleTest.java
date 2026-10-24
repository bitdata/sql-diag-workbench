package com.bitdata.sqlworkbench.sql.decompose;

import com.bitdata.sqlworkbench.sql.model.FragmentType;
import org.apache.calcite.sql.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link CteExpansionRule}.
 *
 * Semantics under test (see docs/decomposition-principles.md):
 * <ul>
 *   <li>"CTE decomposition = drop the body" — the rule emits CTE-definition
 *       fragments only; no "Body" / "with all CTEs" fragment.</li>
 *   <li>Minimum-split (same method): a CTE transitively depended-on by another
 *       sibling CTE is not emitted as a direct sibling.</li>
 *   <li>Orphan CTEs (defined but unused) are still emitted.</li>
 *   <li>A CTE with deps is shaped as {@code WITH <deps...> <its body>}.</li>
 * </ul>
 */
public class CteExpansionRuleTest extends RuleTestFixture {

    private final CteExpansionRule rule = new CteExpansionRule(RuleTestDeps.testResolver());

    @Test
    void expandsSingleCte() throws Exception {
        SqlNode node = parse(
            "WITH v AS (SELECT id FROM users) SELECT * FROM v"
        );
        List<DecomposedFragment> results = rule.apply(node, "postgresql");

        assertEquals(1, results.size(), "Single standalone CTE → 1 fragment (no body)");

        DecomposedFragment def = results.get(0);
        assertEquals(FragmentType.CTE_EXPANDED, def.type());
        assertTrue(def.label().startsWith("CTE:"), "Label should start with CTE: " + def.label());
        assertTrue(def.sqlFragment().toUpperCase().contains("SELECT"),
            "Definition SQL should be a SELECT: " + def.sqlFragment());
        assertFalse(def.sqlFragment().toUpperCase().contains("WITH"),
            "Standalone CTE SQL must not contain WITH: " + def.sqlFragment());
        assertTrue(def.executable());
    }

    @Test
    void expandsMultipleIndependentCtes() throws Exception {
        SqlNode node = parse(
            "WITH a AS (SELECT 1 AS n), b AS (SELECT 2 AS m) SELECT * FROM a, b"
        );
        List<DecomposedFragment> results = rule.apply(node, "postgresql");

        // 2 independent CTEs, no deps between them, body dropped → 2 fragments
        assertEquals(2, results.size(), "Two independent CTEs → 2 fragments (body dropped)");
        assertTrue(results.get(0).label().toLowerCase().contains("a"), "First CTE label: " + results.get(0).label());
        assertTrue(results.get(1).label().toLowerCase().contains("b"), "Second CTE label: " + results.get(1).label());
        // No "Body" / "with all CTEs" sibling
        assertTrue(results.stream().noneMatch(f -> f.label().toLowerCase().contains("body")),
            "No body fragment should be emitted");
    }

    @Test
    void orderByOnOuterIsNoLongerRelevant() throws Exception {
        // The body is dropped, so outer ORDER BY no longer affects emitted fragments.
        // What matters: parse succeeds and we still get CTE fragments (no body sibling).
        SqlNode node = parse(
            "WITH v AS (SELECT id FROM users) SELECT * FROM v ORDER BY id"
        );
        List<DecomposedFragment> results = rule.apply(node, "postgresql");

        assertEquals(1, results.size(), "Outer ORDER BY does not change CTE-fragment count");
        assertFalse(results.get(0).sqlFragment().toUpperCase().contains("ORDER BY"),
            "CTE fragment should not carry the outer ORDER BY");
    }

    @Test
    void nonCteReturnsEmpty() throws Exception {
        SqlNode node = parse("SELECT id FROM users");
        List<DecomposedFragment> results = rule.apply(node, "postgresql");

        assertTrue(results.isEmpty());
    }

    @Test
    void fragmentsAreExecutable() throws Exception {
        SqlNode node = parse(
            "WITH v AS (SELECT id, name FROM users WHERE status = 'active') SELECT * FROM v"
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
    void mysqlDialect() throws Exception {
        SqlNode node = parseMysql(
            "WITH v AS (SELECT id FROM users) SELECT * FROM v"
        );
        List<DecomposedFragment> results = rule.apply(node, "mysql");

        assertEquals(1, results.size());
    }

    @Test
    void programmaticCteExpansion() {
        SqlSelect cteDef = new SqlNodeBuilder()
            .select("id").from("users")
            .where(b -> b.eq("status", "active").build())
            .buildSelect();

        SqlSelect body = new SqlNodeBuilder()
            .select("id").from("v")
            .buildSelect();

        SqlWith node = new SqlNodeBuilder()
            .with("v", cteDef)
            .withBody(body)
            .buildWith();

        List<DecomposedFragment> results = rule.apply(node, "postgresql");

        assertEquals(1, results.size(), "Single CTE, body dropped → 1 fragment");
        assertEquals(FragmentType.CTE_EXPANDED, results.get(0).type());
        assertTrue(results.get(0).sqlFragment().contains("WHERE"));
    }

    // ---- New semantics (2026-06-19) ----

    /**
     * test.txt main scenario: v1 standalone, v3 depends on v2, body references v1+v3.
     * Expected: 2 siblings — v1 (standalone) and v3 (with v2 in WITH); body dropped;
     * v2 NOT a direct sibling (surfaces via re-Expand on v3).
     */
    @Test
    void v1Standalone_v3WithV2_bodyDropped() throws Exception {
        SqlNode node = parse(
            "WITH v1 AS (SELECT e.emp_no, e.last_name FROM employees e " +
            "             INNER JOIN salaries s ON e.emp_no = s.emp_no WHERE gender = 'M'), " +
            "     v2 AS (SELECT d.dept_no, d.dept_name, m.emp_no FROM departments d " +
            "             INNER JOIN dept_manager m ON m.dept_no = d.dept_no), " +
            "     v3 AS (SELECT e.last_name, dept_name, dept_no FROM v2 " +
            "             INNER JOIN employees e ON e.emp_no = v2.emp_no WHERE e.gender = 'F') " +
            "SELECT v1.last_name, v3.dept_name FROM v1 INNER JOIN v3 ON v3.dept_no = v1.emp_no"
        );
        List<DecomposedFragment> results = rule.apply(node, "postgresql");

        assertEquals(2, results.size(),
            "Expected 2 siblings (v1, v3) — v2 is a transitive dep of v3, body is dropped");

        DecomposedFragment v1Fragment = results.stream()
            .filter(f -> f.label().toUpperCase().contains("V1"))
            .findFirst().orElseThrow(() -> new AssertionError("Missing V1 fragment"));
        DecomposedFragment v3Fragment = results.stream()
            .filter(f -> f.label().toUpperCase().contains("V3"))
            .findFirst().orElseThrow(() -> new AssertionError("Missing V3 fragment"));

        assertFalse(v1Fragment.sqlFragment().toUpperCase().contains("WITH"),
            "V1 is standalone — SQL must not contain WITH: " + v1Fragment.sqlFragment());
        assertTrue(v3Fragment.sqlFragment().toUpperCase().startsWith("WITH"),
            "V3 has deps — SQL must start with WITH: " + v3Fragment.sqlFragment());
        assertTrue(v3Fragment.sqlFragment().toUpperCase().contains("V2"),
            "V3 fragment must include V2 definition: " + v3Fragment.sqlFragment());
        assertTrue(v3Fragment.label().contains("with deps"),
            "V3 label should mark dependency presence: " + v3Fragment.label());

        // No "Body" sibling
        assertTrue(results.stream().noneMatch(f -> f.label().toLowerCase().contains("body")),
            "No body fragment should be emitted (CTE decomposition drops the body)");
        // V2 not a direct sibling
        assertTrue(results.stream().noneMatch(f -> f.label().toUpperCase().matches(".*\\bV2\\b.*")
                                                  && !f.label().toUpperCase().contains("V3")),
            "V2 should not be a direct sibling — it surfaces under V3 via recursive Expand");
    }

    /** Orphan CTE — defined but referenced by neither body nor another CTE — still a sibling. */
    @Test
    void orphanCteEmittedAsSibling() throws Exception {
        SqlNode node = parse(
            "WITH unused AS (SELECT 1 AS n), used AS (SELECT 2 AS m) SELECT * FROM used"
        );
        List<DecomposedFragment> results = rule.apply(node, "postgresql");

        assertEquals(2, results.size(),
            "Orphan CTE should still be emitted (faithful to original SQL)");
        assertTrue(results.stream().anyMatch(f -> f.label().toLowerCase().contains("unused")),
            "Orphan 'unused' must appear");
    }

    /**
     * Chain A → B → C: only C (the chain tail) is a direct sibling; A and B
     * surface via recursive Expand on C.
     */
    @Test
    void chainAtoBtoC_onlyTailEmitted() throws Exception {
        SqlNode node = parse(
            "WITH a AS (SELECT 1 AS x), " +
            "     b AS (SELECT x FROM a), " +
            "     c AS (SELECT x FROM b) " +
            "SELECT * FROM c"
        );
        List<DecomposedFragment> results = rule.apply(node, "postgresql");

        assertEquals(1, results.size(),
            "Chain A→B→C: only the tail C is a direct sibling (A, B are transitive deps)");
        DecomposedFragment c = results.get(0);
        assertTrue(c.label().toUpperCase().contains("C"), "Tail must be C: " + c.label());
        String sqlUpper = c.sqlFragment().toUpperCase();
        assertTrue(sqlUpper.contains("A AS ("),
            "C fragment must contain 'A AS (' (definition of A): " + c.sqlFragment());
        assertTrue(sqlUpper.contains("B AS ("),
            "C fragment must contain 'B AS (' (definition of B): " + c.sqlFragment());
        assertTrue(sqlUpper.startsWith("WITH"),
            "C fragment must start with WITH: " + c.sqlFragment());
    }

    /**
     * Guards Option A (inline body): a dependent CTE's fragment is
     * {@code WITH deps... <its body>}, NOT {@code WITH deps... SELECT * FROM cte}.
     */
    @Test
    void dependentCteFragmentIsInlinedRunnable() throws Exception {
        SqlNode node = parse(
            "WITH v1 AS (SELECT 1 AS n), " +
            "     v2 AS (SELECT n FROM v1) " +
            "SELECT * FROM v2"
        );
        List<DecomposedFragment> results = rule.apply(node, "postgresql");

        // v1 is transitively depended-on by v2 → v1 NOT a sibling; v2 only
        assertEquals(1, results.size(), "v1 hidden under v2 — 1 sibling expected");

        DecomposedFragment v2 = results.get(0);
        String sqlUpper = v2.sqlFragment().toUpperCase();
        assertTrue(sqlUpper.startsWith("WITH"),
            "v2 fragment must start with WITH: " + v2.sqlFragment());
        assertTrue(sqlUpper.contains("V1"),
            "v2 fragment must include V1 definition: " + v2.sqlFragment());
        // The dependent CTE itself must NOT be re-defined via SELECT * FROM v2 (Option B is the bug)
        assertFalse(sqlUpper.matches(".*SELECT\\s+\\*\\s+FROM\\s+V2\\b.*"),
            "Option A: must NOT use 'SELECT * FROM v2' wrapping. Actual: " + v2.sqlFragment());
    }

    // ---- P1-9: CTE dependency detection correctness ----

    /**
     * P1-9a regression: a CTE name referenced as a *column* (not a table)
     * must not be treated as a CTE dependency. The previous implementation
     * walked select-list / WHERE / HAVING and treated any matching identifier
     * as a CTE reference if its parent was SqlSelect — which misclassifies
     * column references like {@code SELECT v FROM t} when there is a CTE
     * named v.
     */
    @Test
    void columnSameNameAsCte_isNotDependency() throws Exception {
        SqlNode node = parse(
            "WITH v AS (SELECT 1 AS id FROM users) " +
            "SELECT v AS col_v FROM t"
        );
        List<DecomposedFragment> results = rule.apply(node, "postgresql");

        // v is a standalone CTE (not depended on by anything else), body dropped
        // → 1 sibling. The body column `v` must NOT promote v into a dependency.
        assertEquals(1, results.size(),
            "Column reference 'v' in body must not register as a dependency. " +
            "Got: " + results.stream().map(DecomposedFragment::label).toList());

        // And the v fragment must be the standalone definition, not a WITH-prefixed one
        String sqlUpper = results.get(0).sqlFragment().toUpperCase();
        assertFalse(sqlUpper.startsWith("WITH"),
            "v has no real dependencies — fragment must be standalone (no WITH prefix): "
                + results.get(0).sqlFragment());
    }

    /**
     * P1-9b regression: a recursive CTE (self-reference) must NOT be dropped
     * from decomposition. The previous implementation recorded the self-loop
     * as a dependency, which fed back into referencedByOthers and caused
     * CteExpansionRule to skip the CTE entirely — recursive CTEs disappeared
     * from the decomposition tree.
     */
    @Test
    void recursiveCte_hasVisibleDependencyBoundary() throws Exception {
        SqlNode node = parse(
            "WITH RECURSIVE r AS (" +
            "  SELECT 1 AS n " +
            "  UNION ALL " +
            "  SELECT n + 1 FROM r WHERE n < 10" +
            ") " +
            "SELECT * FROM r"
        );
        RuleEvaluation result = rule.evaluate(node, "postgresql");
        assertTrue(result.fragments().isEmpty());
        assertEquals(1, result.issues().size());
        assertEquals("UNRESOLVED_DEPENDENCY", result.issues().get(0).code());
        assertNotNull(result.issues().get(0).sql());
        assertThrows(IllegalStateException.class, () -> rule.apply(node, "postgresql"));
    }

    /**
     * P1-9a — unit-level inspection of the dependency graph. A bare column
     * reference with the same name as a CTE must NOT register as a CTE
     * dependency.
     */
    @Test
    void dependencyGraph_columnSameNameAsCte_isNotDependency() throws Exception {
        SqlNode node = parse(
            "WITH v AS (SELECT 1 AS id FROM users) " +
            "SELECT v FROM t"
        );
        SqlWith with = (SqlWith) node;
        CteDependencyGraph graph = CteDependencyGraph.build(with, "postgresql");

        assertEquals(Set.of(), graph.transitiveDependencies("V"),
            "Column 'v' in body must not register as a CTE v dependency");
    }

    // ---- P1-9a regression: CTE refs nested inside subqueries ----

    /**
     * P1-9a regression: a CTE referenced inside a scalar subquery in the
     * select-list MUST be recorded as a dependency. The P1-9a fix stopped
     * walking select-list / WHERE / HAVING to avoid misclassifying bare
     * columns — but that also skipped genuine CTE table references nested
     * inside subqueries, so {@code CteExpansionRule} emitted the dependent
     * CTE standalone (no {@code WITH v AS (...)} prefix) → invalid SQL.
     */
    @Test
    void cteRefInSelectListScalarSubquery_isDependency() throws Exception {
        SqlNode node = parse(
            "WITH v AS (SELECT 1 AS id FROM users), " +
            "     c AS (SELECT (SELECT id FROM v) FROM t) " +
            "SELECT * FROM c"
        );
        List<DecomposedFragment> results = rule.apply(node, "postgresql");

        DecomposedFragment c = results.stream()
            .filter(f -> f.label().toUpperCase().contains("C") && !f.label().toUpperCase().contains("V"))
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "Missing C fragment. Got: " + results.stream().map(DecomposedFragment::label).toList()));

        assertTrue(c.sqlFragment().toUpperCase().startsWith("WITH"),
            "C depends on v (via scalar subquery) — fragment must start with WITH: " + c.sqlFragment());
        assertTrue(c.sqlFragment().toUpperCase().contains("V AS"),
            "C fragment must include v's definition: " + c.sqlFragment());
    }

    /**
     * P1-9a regression: CTE reference inside a WHERE IN-subquery.
     */
    @Test
    void cteRefInWhereSubquery_isDependency() throws Exception {
        SqlNode node = parse(
            "WITH v AS (SELECT id FROM users), " +
            "     c AS (SELECT id FROM t WHERE t.x IN (SELECT id FROM v)) " +
            "SELECT * FROM c"
        );
        List<DecomposedFragment> results = rule.apply(node, "postgresql");

        DecomposedFragment c = results.stream()
            .filter(f -> f.label().toUpperCase().contains("C") && !f.label().toUpperCase().contains("V"))
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "Missing C fragment. Got: " + results.stream().map(DecomposedFragment::label).toList()));

        assertTrue(c.sqlFragment().toUpperCase().startsWith("WITH"),
            "C depends on v (via WHERE subquery) — fragment must start with WITH: " + c.sqlFragment());
        assertTrue(c.sqlFragment().toUpperCase().contains("V AS"),
            "C fragment must include v's definition: " + c.sqlFragment());
    }

    /**
     * P1-9a regression — unit level: the dependency graph records a CTE ref
     * nested in a scalar subquery, while still NOT recording a same-name
     * bare column.
     */
    @Test
    void dependencyGraph_cteRefInScalarSubquery_isDependency() throws Exception {
        SqlNode node = parse(
            "WITH v AS (SELECT 1 AS id FROM users), " +
            "     c AS (SELECT (SELECT id FROM v) FROM t) " +
            "SELECT * FROM c"
        );
        SqlWith with = (SqlWith) node;
        CteDependencyGraph graph = CteDependencyGraph.build(with, "postgresql");

        assertEquals(Set.of("V"), graph.transitiveDependencies("C"),
            "C references v via a scalar subquery — must be a dependency");
    }

    /**
     * P1-9a regression — unit level: a CTE ref inside a HAVING subquery is
     * recorded, but a same-name bare column in HAVING is not.
     */
    @Test
    void dependencyGraph_cteRefInHavingSubquery_isDependency() throws Exception {
        SqlNode node = parse(
            "WITH v AS (SELECT id FROM users), " +
            "     c AS (SELECT x, count(*) FROM t GROUP BY x HAVING count(*) IN (SELECT id FROM v)) " +
            "SELECT * FROM c"
        );
        SqlWith with = (SqlWith) node;
        CteDependencyGraph graph = CteDependencyGraph.build(with, "postgresql");

        assertEquals(Set.of("V"), graph.transitiveDependencies("C"),
            "C references v via a HAVING subquery — must be a dependency");
    }

    // ---- P1-9b extension: mutual-recursive 2-cycle ----

    /**
     * P1-9b extension (probe — RED): mutual-recursive 2-cycle
     * {@code v1 → v2 → v1} must NOT silently return an empty fragment list.
     * Current buggy behaviour: {@code referencedByOthers = {v1, v2}} because
     * {@code transitiveDependencies(v1)} walks back via v2 to v1 (back-edge)
     * and adds v1 to its own closure — both CTEs land in the skip set.
     *
     * <p>Captured actual current output (pre-fix, captured by running this
     * test before any SCC implementation):
     * <pre>
     *   results.size() = 0   // empty fragment list, decomposition tree silent
     * </pre>
     *
     * <p>This probe asserts the desired post-fix behaviour
     * ({@code results} must contain at least one fragment for the 2-cycle);
     * currently RED on buggy code, GREEN after 2-β.2 SCC co-emission.
     */
    @Test
    void mutualRecursiveTwoCteCycle_currentlyEmptyFragments() throws Exception {
        SqlNode node = parse(
            "WITH v1 AS (SELECT * FROM v2 WHERE id > 10), " +
            "     v2 AS (SELECT * FROM v1 WHERE id < 100) " +
            "SELECT * FROM v1"
        );
        List<DecomposedFragment> results = rule.apply(node, "postgresql");

        // P1-9b extension: probe assertion (RED until 2-β.2 fix lands).
        // The 2-cycle must surface as at least one SCC fragment — never silently dropped.
        assertFalse(results.isEmpty(),
            "Mutual-recursive 2-cycle must emit ≥1 fragment. " +
            "Buggy current behavior returns empty (both v1, v2 filtered into " +
            "referencedByOthers). Got: " + results.stream().map(DecomposedFragment::label).toList());
    }

    /**
     * P1-9b extension — regression (GREEN): mutual-recursive 2-cycle
     * {@code v1 → v2 → v1} must surface as exactly ONE fragment with both
     * CTEs co-emitted in the same {@code WITH} block. SQL standard requires
     * all members of a recursive cycle to be defined together for the
     * recursive materialization to resolve — splitting them across separate
     * WITH blocks would make each fragment reference an undefined peer.
     *
     * <p>Companion to the probe above: probe asserts non-empty, this test
     * locks the exact shape so future refactors can't silently regress to
     * "two fragments of one CTE each" or "one fragment containing only v1".
     */
    @Test
    void mutualRecursiveTwoCteCycle_emitsOneFragmentContainingBoth() throws Exception {
        SqlNode node = parse(
            "WITH v1 AS (SELECT * FROM v2 WHERE id > 10), " +
            "     v2 AS (SELECT * FROM v1 WHERE id < 100) " +
            "SELECT * FROM v1"
        );
        List<DecomposedFragment> results = rule.apply(node, "postgresql");

        // P1-9b extension: SCC co-emission — the 2-cycle is one indivisible expansion unit.
        assertEquals(1, results.size(),
            "Mutual-recursive 2-cycle must be co-emitted as exactly 1 fragment. " +
            "Got: " + results.stream().map(DecomposedFragment::label).toList());

        DecomposedFragment sccFragment = results.get(0);
        String sqlUpper = sccFragment.sqlFragment().toUpperCase();
        assertTrue(sqlUpper.startsWith("WITH"),
            "SCC fragment must start with WITH (multi-CTE WITH block): " + sccFragment.sqlFragment());
        assertTrue(sqlUpper.contains("V1"),
            "SCC fragment must include v1 definition: " + sccFragment.sqlFragment());
        assertTrue(sqlUpper.contains("V2"),
            "SCC fragment must include v2 definition: " + sccFragment.sqlFragment());
        assertTrue(sqlUpper.contains("V1 AS (") && sqlUpper.contains("V2 AS ("),
            "SCC fragment must define both CTEs as separate AS-clauses: " + sccFragment.sqlFragment());
    }

    /**
     * P1-9b extension — unit-level coverage of the SCC algorithm.
     * Chain A → B → C: no cycles, all three are size-1 SCCs, none should
     * be returned (callers handle non-SCC siblings).
     */
    @Test
    void dependencyGraph_chainReturnsNoSccs() throws Exception {
        SqlNode node = parse(
            "WITH a AS (SELECT 1 AS x), " +
            "     b AS (SELECT x FROM a), " +
            "     c AS (SELECT x FROM b) " +
            "SELECT * FROM c"
        );
        SqlWith with = (SqlWith) node;
        CteDependencyGraph graph = CteDependencyGraph.build(with, "postgresql");

        assertTrue(graph.stronglyConnectedComponents().isEmpty(),
            "Linear chain A→B→C has no cycles — stronglyConnectedComponents must be empty. " +
            "Got: " + graph.stronglyConnectedComponents());
    }

    /**
     * P1-9b extension — unit-level coverage of the SCC algorithm.
     * 2-cycle returns one SCC of size 2 containing both members.
     */
    @Test
    void dependencyGraph_twoCycleReturnsOneSccOfSizeTwo() throws Exception {
        SqlNode node = parse(
            "WITH v1 AS (SELECT * FROM v2 WHERE id > 10), " +
            "     v2 AS (SELECT * FROM v1 WHERE id < 100) " +
            "SELECT * FROM v1"
        );
        SqlWith with = (SqlWith) node;
        CteDependencyGraph graph = CteDependencyGraph.build(with, "postgresql");

        List<Set<String>> sccs = graph.stronglyConnectedComponents();
        assertEquals(1, sccs.size(),
            "2-cycle must produce exactly 1 SCC. Got: " + sccs);
        Set<String> scc = sccs.get(0);
        assertEquals(2, scc.size(),
            "2-cycle SCC must contain 2 members. Got: " + scc);
        assertTrue(scc.contains("V1") && scc.contains("V2"),
            "2-cycle SCC must contain both v1 and v2. Got: " + scc);
    }

    /**
     * P1-9b extension — unit-level coverage of the SCC algorithm.
     * Recursive single-CTE (self-loop) is handled at build time via
     * {@code refs.remove(cteName)}, so Tarjan sees no self-loop and emits
     * a singleton SCC, which the caller filters out (size not > 1) — the
     * recursive CTE falls through to the standalone emission path.
     */
    @Test
    void dependencyGraph_recursiveSingleReturnsNoSccs() throws Exception {
        SqlNode node = parse(
            "WITH RECURSIVE r AS (" +
            "  SELECT 1 AS n " +
            "  UNION ALL " +
            "  SELECT n + 1 FROM r WHERE n < 10" +
            ") " +
            "SELECT * FROM r"
        );
        SqlWith with = (SqlWith) node;
        CteDependencyGraph graph = CteDependencyGraph.build(with, "postgresql");

        // Self-loop was removed by build(); Tarjan sees a singleton — filtered.
        assertTrue(graph.stronglyConnectedComponents().isEmpty(),
            "Self-loop removed by build() — no SCCs should be returned. " +
            "Got: " + graph.stronglyConnectedComponents());
    }
}
