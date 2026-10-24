package com.bitdata.sqlworkbench.diagnostics.rules;

import com.bitdata.sqlworkbench.execution.model.ExecutionResult.PlanNode;
import com.bitdata.sqlworkbench.execution.model.ExecutionResult.Timing;
import com.bitdata.sqlworkbench.sql.model.FragmentNode;
import com.bitdata.sqlworkbench.sql.model.FragmentType;
import com.bitdata.sqlworkbench.sql.model.SourceKind;

import java.util.List;

/**
 * Shared builder helpers for diagnostic rule unit tests.
 */
public final class DiagnosticRuleTestFixture {

    private DiagnosticRuleTestFixture() {}
    static com.bitdata.sqlworkbench.execution.model.NodeMetrics metrics(long estimate,long actual) {
        return new com.bitdata.sqlworkbench.execution.PostgreSqlPlanAdapter().parse(
            "[{\"Plan\":{\"Node Type\":\"Seq Scan\",\"Plan Rows\":"+estimate+",\"Actual Rows\":"+actual+",\"Actual Loops\":1}}]","postgresql").metrics();
    }

    /** Build a PlanNode with only the fields that matter for rule tests. */
    public static PlanNode planNode(String operation, long planRows, long actualRows,
                                     List<PlanNode> children) {
        return new PlanNode(operation, 0.0, 0.0, planRows, actualRows, 0, children,null,null,metrics(planRows,actualRows));
    }

    public static PlanNode planNode(String operation, long planRows, long actualRows) {
        return planNode(operation, planRows, actualRows, List.of());
    }

    /** PlanNode with a structured tableName (JSON EXPLAIN channel shape). */
    public static PlanNode planNodeWithTable(String operation, long planRows, long actualRows,
                                             String tableName) {
        return new PlanNode(operation, 0.0, 0.0, planRows, actualRows, 0, List.of(), tableName,null,metrics(planRows,actualRows));
    }

    /** Build a minimal FragmentNode for rule tests. */
    public static FragmentNode fragment(String id, String label) {
        return new FragmentNode(
            id, FragmentType.ORIGINAL, label, "SELECT 1",
            true, null, 0, null, List.of()
        );
    }

    public static FragmentNode fragment(String id, FragmentType type, String label) {
        return new FragmentNode(
            id, type, label, "SELECT 1",
            true, null, 0, null, List.of()
        );
    }

    /** Build a FragmentNode with custom SQL text (needed for NonSargablePredicateRule tests). */
    public static FragmentNode fragmentWithSql(String id, String sql) {
        return new FragmentNode(
            id, FragmentType.ORIGINAL, id, sql,
            true, null, 0, null, List.of(),
            List.of(), null, SourceKind.AUTO,
            null, null, null, null
        );
    }

    /** Build a FragmentNode with a specific type (needed for trace rule tests). */
    public static FragmentNode fragmentWithType(String id, FragmentType type, String label) {
        return new FragmentNode(
            id, type, label, "SELECT 1",
            true, null, 0, null, List.of(),
            List.of(), null, SourceKind.AUTO,
            null, null, null, null
        );
    }

    public static Timing timing(long durationMs) {
        return new Timing(durationMs);
    }
}
