package com.bitdata.sqlworkbench.sql.decompose;

import com.bitdata.sqlworkbench.fragment.FragmentIdentity;
import com.bitdata.sqlworkbench.sql.diff.SqlCanonicalizer;
import com.bitdata.sqlworkbench.sql.model.FragmentNode;
import org.apache.calcite.sql.parser.SqlParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RootNodeFullSqlTest {

    @Test
    void rootNodeKeepsFullSqlBeyond2000Chars() throws Exception {
        var resolver = RuleTestDeps.testResolver();
        var engine = new DecompositionRuleEngine(
            new ClauseStrippingRule(resolver),
            new SplitSetOpRule(resolver),
            new PredicateDecompositionRule(resolver),
            new CteExpansionRule(resolver),
            new JoinSplitRule(resolver),
            new SubqueryExtractionRule(resolver),
            resolver);

        String longLiteral = "x".repeat(2500);
        String sql = "SELECT '" + longLiteral + "' AS lit FROM t";

        FragmentNode root = engine.createRootNode(SqlParser.create(sql).parseQuery(), "postgresql");

        assertTrue(root.sqlFragment().length() > 2000,
            "Fragment SQL must be stored in full, got length " + root.sqlFragment().length());
        assertFalse(root.sqlFragment().endsWith("..."), "Fragment SQL must not be truncated");
        assertDoesNotThrow(() -> SqlParser.create(root.sqlFragment()).parseQuery(),
            "Stored fragment SQL must stay parseable");
        assertEquals(
            FragmentIdentity.computeId(
                SqlCanonicalizer.canonicalizeSql(root.sqlFragment(), "postgresql"), "postgresql"),
            root.id(),
            "Root id must be computed from the FULL fragment SQL");
    }
}
