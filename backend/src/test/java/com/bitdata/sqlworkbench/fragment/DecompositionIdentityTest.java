package com.bitdata.sqlworkbench.fragment;

import com.bitdata.sqlworkbench.sql.SqlDialectResolver;
import com.bitdata.sqlworkbench.sql.SqlParseEngine;
import com.bitdata.sqlworkbench.sql.decompose.*;
import com.bitdata.sqlworkbench.sql.model.FragmentNode;
import org.apache.calcite.sql.SqlNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DecompositionIdentityTest {

    @Test
    void rootNodeId_is64CharHex() {
        FragmentNode root = createRoot("SELECT * FROM t WHERE a > 0");
        assertTrue(root.id().matches("[a-f0-9]{64}"),
            "root id should be SHA-256 hex (64 chars), got: " + root.id());
    }

    @Test
    void decomposedNodeIds_are64CharHex() {
        List<FragmentNode> nodes = decompose("SELECT * FROM t WHERE a > 0 AND b < 20", "clause-stripping");
        assertFalse(nodes.isEmpty());
        for (FragmentNode n : nodes) {
            assertTrue(n.id().matches("[a-f0-9]{64}"),
                "node id should be SHA-256 hex, got: " + n.id() + " for " + n.label());
        }
    }

    @Test
    void equivalentSql_producesSameRootId() {
        FragmentNode a = createRoot("SELECT * FROM t WHERE a > 0");
        FragmentNode b = createRoot("select * from t where a > 0");
        assertEquals(a.id(), b.id(),
            "equivalent SQL should produce same identity (ADR-0001)");
    }

    private static FragmentNode createRoot(String sql) {
        return newEngine().createRootNode(parse(sql), "postgresql");
    }

    private static List<FragmentNode> decompose(String sql, String ruleName) {
        return newEngine().decompose(parse(sql), "postgresql", ruleName, "test-parent");
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
