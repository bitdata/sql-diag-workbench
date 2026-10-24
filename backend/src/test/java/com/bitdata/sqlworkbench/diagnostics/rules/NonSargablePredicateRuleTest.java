package com.bitdata.sqlworkbench.diagnostics.rules;

import com.bitdata.sqlworkbench.diagnostics.DiagnosticFinding;
import com.bitdata.sqlworkbench.diagnostics.DiagnosticRule;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.bitdata.sqlworkbench.diagnostics.rules.DiagnosticRuleTestFixture.*;
import static org.junit.jupiter.api.Assertions.*;

class NonSargablePredicateRuleTest {

    private final NonSargablePredicateRule rule = new NonSargablePredicateRule();

    // --- Rule metadata ---

    @Test
    void ruleId_isNonSargablePredicate() {
        assertEquals("non-sargable-predicate", rule.id());
    }

    @Test
    void severity_isMedium() {
        assertEquals(DiagnosticRule.Severity.MEDIUM, rule.severity());
    }

    // --- Function-wrapped column detection ---

    @Test
    void detectsFunctionWrappedColumn() {
        var fragment = fragmentWithSql("f1", "SELECT * FROM t WHERE UPPER(name) = 'X'");
        var plan = planNode("Seq Scan on t", 100, 100);

        List<DiagnosticFinding> findings = rule.analyze(fragment, plan, timing(50));

        assertEquals(1, findings.size());
        DiagnosticFinding finding = findings.get(0);
        assertEquals("non-sargable-predicate", finding.ruleId());
        assertTrue(finding.title().contains("function"),
            "Title should mention function, got: " + finding.title());
        assertTrue(finding.message().contains("index usage"),
            "Message should mention index usage");
        // Verify the extracted column name is 'name', not the function name 'UPPER'
        assertTrue(finding.message().contains("'name'"),
            "Message should contain the column name 'name', got: " + finding.message());
        assertEquals(0.8, finding.confidence());
        assertEquals("f1", finding.affectedNodeId());
    }

    @Test
    void detectsLowerFunctionWrappedColumn() {
        var fragment = fragmentWithSql("f1", "SELECT * FROM t WHERE LOWER(email) = 'test@example.com'");
        var plan = planNode("Seq Scan on t", 100, 100);

        List<DiagnosticFinding> findings = rule.analyze(fragment, plan, timing(50));

        assertEquals(1, findings.size());
        assertTrue(findings.get(0).title().contains("function"));
        // Verify the extracted column name is 'email', not the function name 'LOWER'
        assertTrue(findings.get(0).message().contains("'email'"),
            "Message should contain the column name 'email', got: " + findings.get(0).message());
    }

    // --- Leading wildcard LIKE detection ---

    @Test
    void detectsLeadingWildcardLike() {
        var fragment = fragmentWithSql("f1", "SELECT * FROM t WHERE name LIKE '%term'");
        var plan = planNode("Seq Scan on t", 100, 100);

        List<DiagnosticFinding> findings = rule.analyze(fragment, plan, timing(50));

        assertEquals(1, findings.size());
        DiagnosticFinding finding = findings.get(0);
        assertTrue(finding.title().contains("Leading wildcard"),
            "Title should mention leading wildcard, got: " + finding.title());
        assertTrue(finding.message().contains("full-text search"),
            "Message should suggest full-text search");
    }

    @Test
    void detectsLeadingWildcardLike_doubleQuotes() {
        var fragment = fragmentWithSql("f1", "SELECT * FROM t WHERE name LIKE \"%term\"");
        var plan = planNode("Seq Scan on t", 100, 100);

        List<DiagnosticFinding> findings = rule.analyze(fragment, plan, timing(50));

        assertEquals(1, findings.size());
        assertTrue(findings.get(0).title().contains("Leading wildcard"));
    }

    // --- IS NULL detection ---

    @Test
    void detectsIsNullPredicate() {
        var fragment = fragmentWithSql("f1", "SELECT * FROM t WHERE name IS NULL");
        var plan = planNode("Seq Scan on t", 100, 100);

        List<DiagnosticFinding> findings = rule.analyze(fragment, plan, timing(50));

        assertEquals(1, findings.size());
        DiagnosticFinding finding = findings.get(0);
        assertTrue(finding.title().contains("IS NULL"),
            "Title should mention IS NULL, got: " + finding.title());
        assertTrue(finding.message().contains("can use indexes"),
            "Message should not claim IS NULL cannot use indexes");
    }

    // --- Sargable predicates (no finding) ---

    @Test
    void noFinding_forSargablePredicate() {
        var fragment = fragmentWithSql("f1", "SELECT * FROM t WHERE name = 'exact'");
        var plan = planNode("Index Scan on t", 100, 100);

        List<DiagnosticFinding> findings = rule.analyze(fragment, plan, timing(50));

        assertTrue(findings.isEmpty(),
            "Sargable predicate should produce no findings, got: " + findings.size());
    }

    @Test
    void noFinding_forTrailingWildcardLike() {
        // LIKE 'term%' is sargable (trailing wildcard, not leading)
        var fragment = fragmentWithSql("f1", "SELECT * FROM t WHERE name LIKE 'term%'");
        var plan = planNode("Index Scan on t", 100, 100);

        List<DiagnosticFinding> findings = rule.analyze(fragment, plan, timing(50));

        assertTrue(findings.isEmpty(),
            "Trailing wildcard LIKE should produce no findings");
    }

    @Test
    void noFinding_forRangePredicate() {
        var fragment = fragmentWithSql("f1", "SELECT * FROM t WHERE created_at > '2024-01-01'");
        var plan = planNode("Index Scan on t", 100, 100);

        List<DiagnosticFinding> findings = rule.analyze(fragment, plan, timing(50));

        assertTrue(findings.isEmpty(),
            "Range predicate should produce no findings");
    }

    // --- Null / blank SQL ---

    @Test
    void noFinding_whenSqlIsNull() {
        var fragment = fragmentWithSql("f1", null);
        var plan = planNode("Seq Scan on t", 100, 100);

        List<DiagnosticFinding> findings = rule.analyze(fragment, plan, timing(50));

        assertTrue(findings.isEmpty());
    }

    @Test
    void noFinding_whenSqlIsBlank() {
        var fragment = fragmentWithSql("f1", "   ");
        var plan = planNode("Seq Scan on t", 100, 100);

        List<DiagnosticFinding> findings = rule.analyze(fragment, plan, timing(50));

        assertTrue(findings.isEmpty());
    }

    @Test
    void noFinding_whenSqlIsEmpty() {
        var fragment = fragmentWithSql("f1", "");
        var plan = planNode("Seq Scan on t", 100, 100);

        List<DiagnosticFinding> findings = rule.analyze(fragment, plan, timing(50));

        assertTrue(findings.isEmpty());
    }

    // --- Multiple patterns in one SQL ---

    @Test
    void detectsMultiplePatterns_inOneSql() {
        var fragment = fragmentWithSql("f1",
            "SELECT * FROM t WHERE UPPER(name) = 'X' AND email LIKE '%test' AND phone IS NULL");
        var plan = planNode("Seq Scan on t", 100, 100);

        List<DiagnosticFinding> findings = rule.analyze(fragment, plan, timing(50));

        assertEquals(3, findings.size(),
            "Should detect all three non-sargable patterns");

        // Verify each pattern type is represented
        assertTrue(findings.stream().anyMatch(f -> f.title().contains("function")),
            "Should contain function-wrapped finding");
        assertTrue(findings.stream().anyMatch(f -> f.title().contains("Leading wildcard")),
            "Should contain leading wildcard finding");
        assertTrue(findings.stream().anyMatch(f -> f.title().contains("IS NULL")),
            "Should contain IS NULL finding");
    }

    @Test
    void detectsTwoPatterns_functionAndLike() {
        var fragment = fragmentWithSql("f1",
            "SELECT * FROM t WHERE UPPER(name) = 'X' AND city LIKE '%ville'");
        var plan = planNode("Seq Scan on t", 100, 100);

        List<DiagnosticFinding> findings = rule.analyze(fragment, plan, timing(50));

        assertEquals(2, findings.size());
    }

    // --- Finding structure validation ---

    @Test
    void finding_containsSqlFragmentInEvidence() {
        String sql = "SELECT * FROM t WHERE UPPER(name) = 'X'";
        var fragment = fragmentWithSql("f1", sql);
        var plan = planNode("Seq Scan on t", 100, 100);

        List<DiagnosticFinding> findings = rule.analyze(fragment, plan, timing(50));

        assertEquals(1, findings.size());
        DiagnosticFinding finding = findings.get(0);
        assertFalse(finding.evidence().isEmpty(), "Evidence should not be empty");
        assertTrue(finding.evidence().get(0).contains(sql),
            "Evidence should contain the SQL fragment");
    }

    @Test
    void finding_idIncludesFragmentId() {
        var fragment = fragmentWithSql("frag-42", "SELECT * FROM t WHERE name IS NULL");
        var plan = planNode("Seq Scan on t", 100, 100);

        List<DiagnosticFinding> findings = rule.analyze(fragment, plan, timing(50));

        assertEquals(1, findings.size());
        assertTrue(findings.get(0).id().contains("frag-42"),
            "Finding ID should include fragment ID, got: " + findings.get(0).id());
    }

    // --- Null plan handling ---

    @Test
    void noFinding_whenPlanIsNull_withNullSql() {
        var fragment = fragmentWithSql("f1", null);

        List<DiagnosticFinding> findings = rule.analyze(fragment, null, timing(50));

        assertTrue(findings.isEmpty());
    }

    @Test
    void stillDetects_whenPlanIsNull_butSqlHasPattern() {
        // The rule only inspects SQL text, not the plan, so it should still detect
        var fragment = fragmentWithSql("f1", "SELECT * FROM t WHERE name IS NULL");

        List<DiagnosticFinding> findings = rule.analyze(fragment, null, timing(50));

        assertEquals(1, findings.size(),
            "Rule should detect patterns even when plan is null");
    }

    // --- Bug A probes (P2-11): IS NULL must be detected even when not at end of predicate ---

    @Test
    void detectsIsNullNotAtEndOfPredicate() {
        var fragment = fragmentWithSql("f1", "SELECT * FROM t WHERE x IS NULL AND y = 1");
        var plan = planNode("Seq Scan on t", 100, 100);

        List<DiagnosticFinding> findings = rule.analyze(fragment, plan, timing(50));

        assertEquals(1, findings.size(),
            "IS NULL 不在末尾时也应检出，got: " + findings);
        assertEquals("non-sargable-predicate", findings.get(0).ruleId());
    }

    @Test
    void stillDetectsIsNullAtEndOfPredicate() {
        var fragment = fragmentWithSql("f2", "SELECT * FROM t WHERE x IS NULL");
        var plan = planNode("Seq Scan on t", 100, 100);

        List<DiagnosticFinding> findings = rule.analyze(fragment, plan, timing(50));

        assertEquals(1, findings.size(), "末尾 IS NULL 的既有行为不变");
    }

    // --- Bug B probes (P2-11): aggregates in HAVING must NOT be flagged ---

    @Test
    void doesNotFlagAggregateInHaving() {
        var fragment = fragmentWithSql("f3", "SELECT g FROM t GROUP BY g HAVING count(*) = 5");
        var plan = planNode("Seq Scan on t", 100, 100);

        List<DiagnosticFinding> findings = rule.analyze(fragment, plan, timing(50));

        assertTrue(findings.isEmpty(),
            "聚合函数不是非 SARGABLE 谓词，got: " + findings);
    }

    @Test
    void doesNotFlagAggregateInHaving_uppercase() {
        // Controller-added probe: ensure the negative-lookbehind guard holds under (?i)
        // for uppercase aggregate names (e.g. COUNT(*)).
        var fragment = fragmentWithSql("f3u", "SELECT g FROM t GROUP BY g HAVING COUNT(*) = 5");
        var plan = planNode("Seq Scan on t", 100, 100);

        List<DiagnosticFinding> findings = rule.analyze(fragment, plan, timing(50));

        assertTrue(findings.isEmpty(),
            "大写聚合函数也不应被标记，got: " + findings);
    }

    @Test
    void doesNotFlagOtherAggregates() {
        var plan = planNode("Seq Scan on t", 100, 100);
        for (String agg : new String[] {"sum(x)", "avg(x)", "min(x)", "max(x)"}) {
            var fragment = fragmentWithSql("f4",
                "SELECT g FROM t GROUP BY g HAVING " + agg + " = 5");
            List<DiagnosticFinding> findings = rule.analyze(fragment, plan, timing(50));
            assertTrue(findings.isEmpty(), agg + " 不应被标记，got: " + findings);
        }
    }
}
