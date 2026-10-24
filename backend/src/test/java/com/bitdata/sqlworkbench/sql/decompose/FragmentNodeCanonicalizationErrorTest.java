package com.bitdata.sqlworkbench.sql.decompose;

import com.bitdata.sqlworkbench.fragment.FragmentIdentity;
import com.bitdata.sqlworkbench.sql.SqlParseEngine;
import com.bitdata.sqlworkbench.sql.diff.SqlCanonicalizer;
import com.bitdata.sqlworkbench.sql.model.FragmentNode;
import com.bitdata.sqlworkbench.sql.model.FragmentType;
import com.bitdata.sqlworkbench.sql.model.SourceKind;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.parser.SqlParser;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Task 4c — the SCC (bodyless {@code WITH}) fragment emitted by
 * {@link CteExpansionRule#buildSccFragment} reaches the engine's canonicalization
 * fallback (probe task 4b: it throws {@code SqlParseException} standalone). Its
 * degradation must be <em>observable on the returned structure</em>, not only
 * logged (spec §4.2 / rule #2 "Observable degradation").
 *
 * <p>Ruling 2 (flag-only): the node identity is unchanged — it is still derived
 * from the raw fragment text. Only {@code canonicalizationError} is new.
 */
class FragmentNodeCanonicalizationErrorTest {

    /** A mutually-recursive CTE pair → one SCC fragment with no body SELECT. */
    private static final String MUTUAL_RECURSIVE =
        "WITH v1 AS (SELECT * FROM v2 WHERE id > 10), " +
        "     v2 AS (SELECT * FROM v1 WHERE id < 100) " +
        "SELECT * FROM v1";

    private static final String CLEAN_CTE =
        "WITH v AS (SELECT id FROM users) SELECT * FROM v";

    private static SqlNode parse(String sql) throws Exception {
        return SqlParser.create(sql, SqlParser.config().withCaseSensitive(true)).parseQuery();
    }

    private static DecompositionRuleEngine engine() {
        var resolver = RuleTestDeps.testResolver();
        return new DecompositionRuleEngine(
            new ClauseStrippingRule(resolver),
            new SplitSetOpRule(resolver),
            new PredicateDecompositionRule(resolver),
            new CteExpansionRule(resolver),
            new JoinSplitRule(resolver),
            new SubqueryExtractionRule(resolver),
            resolver);
    }

    private static FragmentNode findScc(FragmentNode node) {
        if (node.label() != null && node.label().contains("CTE SCC")) return node;
        for (FragmentNode c : node.children()) {
            FragmentNode f = findScc(c);
            if (f != null) return f;
        }
        return null;
    }

    private static List<FragmentNode> flatten(FragmentNode node) {
        List<FragmentNode> all = new ArrayList<>();
        all.add(node);
        for (FragmentNode c : node.children()) all.addAll(flatten(c));
        return all;
    }

    // ---- Step 1: the degradation is observable on the returned structure ----

    /**
     * Rf (fix round 3): spec §10 claims the fallback reproduces on both dialects, but
     * the regression only ran postgresql, so the claim had no durable anchor (the mysql
     * measurement lived in a git-ignored probe output). Mutually-recursive CTEs yield
     * the same bodyless {@code WITH} under mysql, so the degradation must be observable
     * there too. Parsed through the production entry point ({@link SqlParseEngine#parse}).
     */
    @Test
    void sccFragmentFromBuild_carriesCanonicalizationError_mysql() throws Exception {
        SqlNode parsed = new SqlParseEngine(RuleTestDeps.testResolver())
            .parse(MUTUAL_RECURSIVE, "mysql");
        FragmentNode root = engine().build(parsed, "mysql");
        FragmentNode scc = findScc(root);
        assertNotNull(scc, "expected a 'CTE SCC' child in the mysql-built tree");
        String error = scc.canonicalizationError();
        assertTrue(error != null && !error.isBlank(),
            "mysql leg: the SCC fragment's identity is a raw-SQL fallback there too; was: " + error);
    }

    @Test
    void sccFragmentFromBuild_carriesCanonicalizationError() throws Exception {
        FragmentNode root = engine().build(parse(MUTUAL_RECURSIVE), "postgresql");
        FragmentNode scc = findScc(root);
        assertNotNull(scc, "expected a 'CTE SCC' child in the built tree");
        assertNotNull(scc.canonicalizationError(),
            "the SCC fragment's identity is a raw-SQL fallback — it must be observable, "
                + "not only log.debug'd. Node: " + scc.label());
    }

    @Test
    void sccFragmentCanonicalizationError_isNotBlank() throws Exception {
        FragmentNode root = engine().build(parse(MUTUAL_RECURSIVE), "postgresql");
        FragmentNode scc = findScc(root);
        assertNotNull(scc, "expected a 'CTE SCC' child in the built tree");
        String error = scc.canonicalizationError();
        assertTrue(error != null && !error.isBlank(),
            "canonicalizationError must carry a non-blank reason; was: " + error);
    }

    /**
     * Reverse constraint: every parsable node — root included ({@code flatten}
     * starts at the root) — must have a null marker.
     *
     * <p>Replaces the former {@code parsableRootInSameTree_hasNullCanonicalizationError}:
     * that test asserted the {@code build()} root of {@code MUTUAL_RECURSIVE}, but
     * {@code build()} rebuilds the root through the 9-arg constructor, which hardcodes
     * the marker to null. It could therefore never fail (F3, fix round 2).
     */
    @Test
    void cleanTree_hasNoCanonicalizationErrorOnAnyNode() throws Exception {
        FragmentNode root = engine().build(parse(CLEAN_CTE), "postgresql");
        for (FragmentNode node : flatten(root)) {
            assertNull(node.canonicalizationError(),
                "clean fragment must have a null marker: " + node.label());
        }
    }

    // ---- F2 (fix round 2): the dispatch-fallback source (form.degraded()) ----

    /**
     * Direct seam for the second degradation source: a canonical form carrying
     * unsupported node types must yield a non-null marker naming them. This drives
     * the mapping without depending on which node types the dispatcher currently
     * misses (see {@link #explainRoot_reachesDispatchFallbackBranch} for the
     * end-to-end reachability measurement).
     */
    @Test
    void degradationMarkerForDegradedForm_namesUnsupportedTypes() {
        SqlCanonicalizer.CanonicalForm form = new SqlCanonicalizer.CanonicalForm(
            "UNKNOWN@root:SqlUpdate#431176b14cd3", List.of("SqlUpdate#431176b14cd3"));
        String marker = DecompositionRuleEngine.degradationMarker(form);
        assertNotNull(marker, "a degraded canonical form must produce a marker");
        assertTrue(marker.contains("SqlUpdate#431176b14cd3"),
            "the marker must name the unsupported token(s); was: " + marker);
    }

    @Test
    void degradationMarkerForCleanForm_isNull() {
        SqlCanonicalizer.CanonicalForm form = new SqlCanonicalizer.CanonicalForm(
            "SELECT@root:SELECT", List.of());
        assertNull(DecompositionRuleEngine.degradationMarker(form),
            "an UNKNOWN-free canonical form must not be marked");
    }

    /**
     * Reachability, measured (probe, fix round 2) — not assumed, and parsed through the
     * real production entry point ({@link SqlParseEngine#parse}), not a test-local
     * {@code SqlParser}. {@code SqlExplain} is not dispatched by {@code AstSerializer},
     * so the root's canonical form is degraded (probe measured token
     * {@code SqlExplain#d6d785c7694d}).
     */
    @Test
    void explainRoot_reachesDispatchFallbackBranch() throws Exception {
        SqlNode parsed = new SqlParseEngine(RuleTestDeps.testResolver())
            .parse("EXPLAIN PLAN FOR SELECT 1", "postgresql");
        FragmentNode root = engine().createRootNode(parsed, "postgresql");
        String marker = root.canonicalizationError();
        assertNotNull(marker,
            "SqlExplain is undispatched ⇒ the degraded() branch must mark the root. SQL: "
                + root.sqlFragment());
        assertTrue(marker.contains("SqlExplain"),
            "the marker must name the unsupported token; was: " + marker);
    }

    // ---- F5 (fix round 2): marker totality when the exception message is null ----

    @Test
    void degradationMarkerForException_nullMessage_fallsBackToClassName() {
        String marker = DecompositionRuleEngine.degradationMarker(new IllegalStateException());
        assertTrue(marker != null && !marker.isBlank(),
            "a null exception message must not produce a null marker — the degradation "
                + "would collapse back to log-only; was: " + marker);
        assertEquals("IllegalStateException", marker);
    }

    @Test
    void degradationMarkerForException_blankMessage_fallsBackToClassName() {
        String marker = DecompositionRuleEngine.degradationMarker(new IllegalStateException("   "));
        assertTrue(marker != null && !marker.isBlank(),
            "a blank message is equally uninformative; was: [" + marker + "]");
        assertEquals("IllegalStateException", marker);
    }

    @Test
    void degradationMarkerForException_carriesMessageWhenPresent() {
        String message = "Encountered \"<EOF>\" at line 5, column 15.";
        assertEquals(message,
            DecompositionRuleEngine.degradationMarker(new IllegalArgumentException(message)));
    }

    // ---- Ruling 2: flag-only — identity unchanged ----

    @Test
    void sccFragmentId_isStillDerivedFromRawFragment() throws Exception {
        FragmentNode root = engine().build(parse(MUTUAL_RECURSIVE), "postgresql");
        FragmentNode scc = findScc(root);
        assertNotNull(scc);
        assertEquals(FragmentIdentity.computeId(scc.sqlFragment(), "postgresql"), scc.id(),
            "flag-only ruling: the id must still be derived from the raw fragment text");
    }

    /**
     * Frozen value measured by probe task 4b (postgresql, raw-fallback identity).
     * A future identity change (rejected for now, ruling 2) would surface here.
     */
    @Test
    void sccFragmentId_matchesProbeMeasuredValue() throws Exception {
        FragmentNode root = engine().build(parse(MUTUAL_RECURSIVE), "postgresql");
        FragmentNode scc = findScc(root);
        assertNotNull(scc);
        assertEquals("48121708cbc57c2b68a0cf3ba92ed1ef14cd6f83d1d129b69c127329ee2a8ccc", scc.id(),
            "identity must be unchanged from the task-4b measurement (flag-only)");
    }

    // ---- Step 3: SCC fragment is not claimable as runnable ----

    @Test
    void sccFragmentFromDecompose_isNotExecutable() throws Exception {
        List<FragmentNode> nodes = engine().decompose(
            parse(MUTUAL_RECURSIVE), "postgresql", "cte-expansion", "parent-1");
        assertEquals(1, nodes.size(), "SCC co-emission yields exactly one fragment");
        FragmentNode scc = nodes.get(0);
        assertFalse(scc.executable(),
            "a bodyless WITH cannot be run standalone — executable must be false. SQL: "
                + scc.sqlFragment());
        assertNotNull(scc.canonicalizationError(),
            "the same fragment must also surface its identity degradation");
    }

    /**
     * F1 (fix round 1) — the {@code build()} entry point must agree with
     * {@code decompose()}: the same SCC node must not report executable=true on
     * one path and false on the other. {@code classifyChildren} used to hardcode
     * {@code true} while rebuilding the top-level children.
     */
    @Test
    void sccFragmentFromBuild_isNotExecutable() throws Exception {
        FragmentNode root = engine().build(parse(MUTUAL_RECURSIVE), "postgresql");
        FragmentNode scc = findScc(root);
        assertNotNull(scc, "expected a 'CTE SCC' child in the built tree");
        assertFalse(scc.executable(),
            "build() must not resurrect executable=true for the bodyless-WITH SCC "
                + "node — decompose() already reports false. SQL: " + scc.sqlFragment());
    }

    /**
     * Reverse pin for F1: propagating {@code child.executable()} must not turn
     * off executable for ordinary nodes. Every rule except cte-expansion's SCC
     * emits {@code true}, so a clause-stripping child stays executable.
     */
    @Test
    void buildPath_ordinaryChild_isStillExecutable() throws Exception {
        FragmentNode root = engine().build(
            parse("SELECT id FROM t WHERE a > 0"), "postgresql");
        FragmentNode stripped = flatten(root).stream()
            .filter(n -> n.ruleName() != null && n.ruleName().equals("clause-stripping"))
            .findFirst().orElseThrow(() ->
                new AssertionError("expected a clause-stripping child; got: "
                    + flatten(root).stream().map(FragmentNode::label).toList()));
        assertTrue(stripped.executable(),
            "ordinary fragments stay executable after propagating child.executable(): "
                + stripped.label());
    }

    // ---- Step 2: Jackson round-trip measurements (no assumptions) ----

    /** A node carrying the marker survives serialize → deserialize. */
    @Test
    void jacksonRoundTrip_preservesCanonicalizationError() throws Exception {
        FragmentNode node = new FragmentNode(
            "id-1", FragmentType.CTE_EXPANDED, "CTE SCC: V1+V2 (mutually recursive)",
            "WITH v1 AS (SELECT * FROM v2), v2 AS (SELECT * FROM v1)",
            false, null, 0, "cte-expansion", List.of(),
            List.of("parent-1"), null, SourceKind.AUTO,
            "WITH V1 AS (SELECT * FROM V2)", null, "2026-10-02T00:00:00Z", null,
            "Encountered \"<EOF>\" at line 1, column 1.");

        ObjectMapper mapper = new ObjectMapper();
        String json = mapper.writeValueAsString(node);
        assertTrue(json.contains("\"canonicalizationError\""),
            "marker must be present in JSON when non-null; JSON was: " + json);

        FragmentNode back = mapper.readValue(json, FragmentNode.class);
        assertEquals(node.canonicalizationError(), back.canonicalizationError(),
            "Jackson must use the canonical (17-arg) record creator, not the 16-arg "
                + "compatibility constructor — otherwise the marker is silently lost");
        assertEquals(node.id(), back.id());
        assertEquals(node.executable(), back.executable());
        assertEquals(node.canonicalSql(), back.canonicalSql());
    }

    /**
     * Old persisted JSON has no {@code canonicalizationError} key (non_null
     * inclusion omits it). Deserialization must tolerate its absence.
     */
    @Test
    void jacksonLegacyJson_missingMarker_deserializesToNull() throws Exception {
        String legacyJson = "{\"id\":\"id-1\",\"type\":\"CTE_EXPANDED\",\"label\":\"l\","
            + "\"sqlFragment\":\"SELECT 1\",\"executable\":true,\"nodeCount\":0,"
            + "\"children\":[],\"parentIds\":[],\"source\":\"AUTO\"}";
        FragmentNode legacy = new ObjectMapper().readValue(legacyJson, FragmentNode.class);
        assertNull(legacy.canonicalizationError(),
            "a legacy node without the key must deserialize to a null marker");
    }

    /**
     * Mirrors {@code spring.jackson.default-property-inclusion: non_null}: a null
     * marker is ABSENT from the JSON, not present-and-null.
     */
    @Test
    void jacksonNonNullInclusion_omitsNullMarker() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        mapper.setDefaultPropertyInclusion(JsonInclude.Include.NON_NULL);

        FragmentNode clean = new FragmentNode(
            "id-2", FragmentType.ORIGINAL, "Original Query", "SELECT 1", true,
            null, 0, null, List.of(), List.of(), null, SourceKind.AUTO,
            "SELECT@root:SELECT", null, "2026-10-02T00:00:00Z", null, null);

        String json = mapper.writeValueAsString(clean);
        assertFalse(json.contains("canonicalizationError"),
            "under non_null inclusion a null marker must be absent; JSON was: " + json);
    }
}
