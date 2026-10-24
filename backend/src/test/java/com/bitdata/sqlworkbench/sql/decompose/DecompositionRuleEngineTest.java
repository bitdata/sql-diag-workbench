package com.bitdata.sqlworkbench.sql.decompose;

import com.bitdata.sqlworkbench.sql.model.FragmentNode;
import org.apache.calcite.sql.parser.SqlParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Engine-level pins for root-node construction
 * ({@link DecompositionRuleEngine#createRootNode}).
 */
class DecompositionRuleEngineTest {

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

    /**
     * PR-3 7c (follow-up 7): a top-level {@code ORDER BY} over a {@code WITH}
     * query parses as {@code SqlOrderBy(query=SqlWith)}. Before the fix that
     * shape matched no branch and fell through to the {@code "Original SQL"}
     * fallback, so the root {@code ORIGINAL} fragment of a CTE query was
     * mislabelled.
     *
     * <p>{@code buildRootLabel} is {@code private} — this pins the behaviour
     * through the public {@code createRootNode} entry point rather than widening
     * production visibility for a test.
     */
    @Test
    void buildRootLabel_unwrapsSqlOrderBy_wrappingSqlWith() throws Exception {
        String sql = "WITH a AS (SELECT 1 AS x FROM t) SELECT x FROM a ORDER BY x";

        FragmentNode root = engine().createRootNode(SqlParser.create(sql).parseQuery(), "postgresql");

        assertEquals("Original CTE Query", root.label(),
            "SqlOrderBy(SqlWith) must unwrap to 'Original CTE Query' (was the 'Original SQL' fallback)");
    }
}
