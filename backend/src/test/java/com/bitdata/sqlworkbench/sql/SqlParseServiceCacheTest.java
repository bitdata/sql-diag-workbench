package com.bitdata.sqlworkbench.sql;

import com.bitdata.sqlworkbench.sql.decompose.*;
import com.bitdata.sqlworkbench.sql.model.ParseRequest;
import com.bitdata.sqlworkbench.sql.model.ParseResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SqlParseServiceCacheTest {

    @Test
    void hashCodeCollisionDoesNotPoisonCache() {
        // Test prerequisite: "Aa" and "BB" have identical String.hashCode(),
        // so the old cache key (sql.hashCode()) mapped both to one entry.
        assertEquals("Aa".hashCode(), "BB".hashCode(), "known collision pair");

        var resolver = RuleTestDeps.testResolver();
        var ruleEngine = new DecompositionRuleEngine(
            new ClauseStrippingRule(resolver),
            new SplitSetOpRule(resolver),
            new PredicateDecompositionRule(resolver),
            new CteExpansionRule(resolver),
            new JoinSplitRule(resolver),
            new SubqueryExtractionRule(resolver),
            resolver);
        var service = new SqlParseService(
            new SqlParseEngine(resolver),
            new DecompositionService(ruleEngine),
            resolver);

        ParseResult first = service.parse(new ParseRequest("SELECT * FROM Aa", "postgresql"));
        ParseResult second = service.parse(new ParseRequest("SELECT * FROM BB", "postgresql"));

        assertTrue(first.tree().sqlFragment().toUpperCase().contains("AA"),
            first.tree().sqlFragment());
        assertTrue(second.tree().sqlFragment().toUpperCase().contains("BB"),
            "Cache collision served Aa's result for BB: " + second.tree().sqlFragment());
        assertNotEquals(first.tree().id(), second.tree().id());
    }
}
