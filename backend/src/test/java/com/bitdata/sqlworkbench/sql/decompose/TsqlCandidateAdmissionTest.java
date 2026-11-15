package com.bitdata.sqlworkbench.sql.decompose;

import com.bitdata.sqlworkbench.connection.DatabaseStrategyFactory;
import com.bitdata.sqlworkbench.sql.SqlDialectResolver;
import com.bitdata.sqlworkbench.sql.diff.StructuralSqlCodec;
import com.bitdata.sqlworkbench.sql.model.FragmentType;
import org.apache.calcite.sql.SqlNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TsqlCandidateAdmissionTest {
    private final SqlDialectResolver resolver = new SqlDialectResolver(DatabaseStrategyFactory.withBuiltinStrategies());
    private final ClauseStrippingRule clause = new ClauseStrippingRule(resolver);
    private static final String PAGED = "SELECT id FROM t ORDER BY id OFFSET 1 ROWS FETCH NEXT 1 ROWS ONLY";

    @Test
    void clauseEvaluationRetainsSafeSiblingAndReportsBothNestedConstraints() throws Exception {
        var top = clause.evaluate(StructuralSqlCodec.parse(PAGED, "sqlserver"), "sqlserver");
        assertEquals(1, top.fragments().size());
        assertEquals("RETAINED_OFFSET_FETCH_REQUIRES_ORDER_BY", top.issues().get(0).code());
        assertNotNull(top.issues().get(0).sql());
        assertNotNull(top.issues().get(0).scopePath());
        assertThrows(IllegalStateException.class, () -> clause.apply(StructuralSqlCodec.parse(PAGED, "sqlserver"), "sqlserver"));

        var nested = clause.evaluate(StructuralSqlCodec.parse("WITH c AS (" + PAGED + ") SELECT * FROM c", "sqlserver"), "sqlserver");
        assertTrue(nested.fragments().isEmpty());
        assertEquals(2, nested.issues().size());
        assertTrue(nested.issues().stream().anyMatch(i -> i.code().equals("NESTED_ORDER_BY_REQUIRES_LIMITER")));
    }

    @Test
    void topWithoutOffsetDoesNotLoseSafeCandidates() throws Exception {
        var result = clause.evaluate(StructuralSqlCodec.parse("SELECT TOP (1) id FROM t ORDER BY id", "sqlserver"), "sqlserver");
        assertTrue(result.issues().isEmpty());
        assertEquals(2, result.fragments().size());
    }

    @Test
    void engineAppliesSameAdmissionToOtherRulesAndOptionScan() throws Exception {
        // Deliberately invalid output from a different rule proves admission is shared.
        var join = new JoinSplitRule(resolver) {
            @Override public RuleEvaluation evaluate(SqlNode root, String dialect) {
                return new RuleEvaluation(List.of(new DecomposedFragment(FragmentType.JOIN_SPLIT,
                    "invalid", "SELECT id FROM t OFFSET 1 ROWS", name(), true, null, List.of())), List.of());
            }
        };
        var engine = new DecompositionRuleEngine(clause, new SplitSetOpRule(resolver),
            new PredicateDecompositionRule(resolver), new CteExpansionRule(resolver), join,
            new SubqueryExtractionRule(resolver), resolver);
        var ast = StructuralSqlCodec.parse("SELECT id FROM t", "sqlserver");
        var batch = engine.decomposeDetailed(ast, "SELECT id FROM t", "sqlserver", join.name(), null);
        assertEquals("PARTIAL", batch.status());
        assertTrue(batch.children().isEmpty());
        assertEquals("RETAINED_OFFSET_FETCH_REQUIRES_ORDER_BY", batch.issues().get(0).code());
        var options = engine.scanOptions(ast, "sqlserver");
        assertEquals("PARTIAL", options.scan().status());
        assertTrue(options.options().stream().anyMatch(o -> o.ruleName().equals(join.name())));
        assertTrue(options.scan().issues().stream().anyMatch(i -> i.code().equals(batch.issues().get(0).code())));
    }
}
