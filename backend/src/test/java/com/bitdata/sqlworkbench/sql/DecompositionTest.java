package com.bitdata.sqlworkbench.sql;

import com.bitdata.sqlworkbench.sql.decompose.*;
import com.bitdata.sqlworkbench.sql.model.FragmentNode;

public class DecompositionTest {

    public static void main(String[] args) {
        String cteSql = """
            WITH V AS(
                SELECT date_trunc('day', insert_time) as day_date, name, count(DISTINCT item) as dailyCount
                FROM monitor_data_log
                WHERE insert_time is not null
                GROUP BY day_date,name
            )
            SELECT day_date, sum(dailyCount) as dailyCount
            FROM V
            GROUP BY day_date
            ORDER BY day_date
            """;

        String simpleSql = """
            SELECT id, name, email
            FROM users
            WHERE status = 'active'
            AND email IS NOT NULL
            ORDER BY created_at DESC
            """;

        System.out.println("=== Testing Simple SQL ===");
        testDecomposition(simpleSql);

        System.out.println("\n=== Testing CTE SQL ===");
        testDecomposition(cteSql);
    }

    private static void testDecomposition(String sql) {
        try {
            SqlParseEngine parseEngine = new SqlParseEngine(com.bitdata.sqlworkbench.sql.decompose.RuleTestDeps.testResolver());

            org.apache.calcite.sql.SqlNode sqlNode = parseEngine.parse(sql, "postgresql");
            System.out.println("Parsed node type: " + sqlNode.getClass().getSimpleName());

            ClauseStrippingRule clauseRule = new ClauseStrippingRule(com.bitdata.sqlworkbench.sql.decompose.RuleTestDeps.testResolver());
            SplitSetOpRule splitRule = new SplitSetOpRule(com.bitdata.sqlworkbench.sql.decompose.RuleTestDeps.testResolver());
            PredicateDecompositionRule predicateRule = new PredicateDecompositionRule(com.bitdata.sqlworkbench.sql.decompose.RuleTestDeps.testResolver());

            System.out.println("--- ClauseStrippingRule ---");
            var clauseResults = clauseRule.apply(sqlNode, "postgresql");
            System.out.println("Results: " + clauseResults.size());
            for (var r : clauseResults) {
                System.out.println("  - " + r.label());
                System.out.println("    SQL length: " + r.sqlFragment().length());
                System.out.println("    SQL: " + r.sqlFragment());
            }

            System.out.println("--- SplitSetOpRule ---");
            var splitResults = splitRule.apply(sqlNode, "postgresql");
            System.out.println("Results: " + splitResults.size());

            System.out.println("--- PredicateDecompositionRule ---");
            var predicateResults = predicateRule.apply(sqlNode, "postgresql");
            System.out.println("Results: " + predicateResults.size());
            for (var r : predicateResults) {
                System.out.println("  - " + r.label());
            }

        } catch (Exception e) {
            System.out.println("Error: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
