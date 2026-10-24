package com.bitdata.sqlworkbench.sql.decompose;

import com.bitdata.sqlworkbench.sql.SqlParseEngine;
import com.bitdata.sqlworkbench.sql.SqlDialectResolver;
import com.bitdata.sqlworkbench.sql.model.FragmentNode;
import org.apache.calcite.sql.SqlNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Spec §4.2 — every fragment node created by the engine must carry
 * canonicalSql (ADR-0001) and createdAt (ISO 8601) populated at construction.
 */
class FragmentNodeLineageFieldsTest {

    @Test
    void decomposedNodes_haveCanonicalSqlAndCreatedAt() {
        DecompositionRuleEngine engine = newEngine();
        SqlNode root = parse("SELECT * FROM t WHERE a>0 AND b<20");
        List<FragmentNode> decomposed = engine.decompose(root, "postgresql", "clause-stripping", "parent-1");
        assertFalse(decomposed.isEmpty(), "rule should produce at least one child");

        for (FragmentNode node : decomposed) {
            assertNotNull(node.canonicalSql(),
                "canonicalSql missing on node " + node.label());
            assertFalse(node.canonicalSql().isBlank(),
                "canonicalSql is blank on node " + node.label());
            assertNotNull(node.createdAt(),
                "createdAt missing on node " + node.label());
            // ISO 8601 (Instant.toString) looks like "2026-06-19T22:00:00.123Z"
            assertTrue(node.createdAt().matches("\\d{4}-\\d{2}-\\d{2}T.*Z"),
                "createdAt is not ISO 8601 UTC: " + node.createdAt());
        }
    }

    @Test
    void canonicalSql_isStableAcrossEquivalentQueries() {
        DecompositionRuleEngine engine = newEngine();
        // Two equivalent SQLs (different case + whitespace) must canonicalize
        // to the same form, so identity (ADR-0001) is preserved.
        FragmentNode a = engine.createRootNode(
            parse("SELECT * FROM t WHERE a>0 AND b<20"), "postgresql");
        FragmentNode b = engine.createRootNode(
            parse("select * from t WHERE a>0 and b<20"), "postgresql");
        assertEquals(a.canonicalSql(), b.canonicalSql());
    }

    private static SqlNode parse(String sql) {
        try {
            return new SqlParseEngine(RuleTestDeps.testResolver()).parse(sql, "postgresql");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static DecompositionRuleEngine newEngine() {
        SqlDialectResolver resolver = RuleTestDeps.testResolver();
        return new DecompositionRuleEngine(
            new ClauseStrippingRule(resolver),
            new SplitSetOpRule(resolver),
            new PredicateDecompositionRule(resolver),
            new CteExpansionRule(resolver),
            new JoinSplitRule(resolver),
            new SubqueryExtractionRule(resolver),
            resolver
        );
    }
}
