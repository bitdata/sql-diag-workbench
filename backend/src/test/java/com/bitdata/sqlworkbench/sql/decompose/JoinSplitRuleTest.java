package com.bitdata.sqlworkbench.sql.decompose;

import com.bitdata.sqlworkbench.sql.model.FragmentType;
import org.apache.calcite.sql.*;
import org.apache.calcite.sql.parser.SqlParser;
import org.apache.calcite.sql.parser.SqlParserPos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class JoinSplitRuleTest extends RuleTestFixture {

    private final JoinSplitRule rule = new JoinSplitRule(RuleTestDeps.testResolver());

    @Test
    void splitsInnerJoin() throws Exception {
        SqlNode node = parse(
            "SELECT * FROM users INNER JOIN orders ON users.id = orders.user_id"
        );
        List<DecomposedFragment> results = rule.apply(node, "postgresql");

        assertEquals(2, results.size());
        assertTrue(results.stream().allMatch(f -> f.type() == FragmentType.JOIN_SPLIT || f.type() == FragmentType.COLUMN_PRUNED));
        assertTrue(results.stream().allMatch(f -> f.label().startsWith("Remove ")));
    }

    @Test
    void splitsLeftJoin() throws Exception {
        SqlNode node = parse(
            "SELECT * FROM users LEFT JOIN orders ON users.id = orders.user_id"
        );
        List<DecomposedFragment> results = rule.apply(node, "postgresql");

        assertEquals(2, results.size());
        assertTrue(results.stream().anyMatch(f -> f.label().startsWith("Remove ")));
    }

    @Test
    void splitsThreeTableJoin() throws Exception {
        // b is a bridge table between a and c — removing b disconnects the graph,
        // so bridge detection skips that fragment. We get 2 fragments (remove a, remove c).
        SqlNode node = parse(
            "SELECT * FROM a INNER JOIN b ON a.id = b.a_id INNER JOIN c ON b.id = c.b_id"
        );
        List<DecomposedFragment> results = rule.apply(node, "postgresql");

        assertEquals(2, results.size(), "Bridge table b skipped, yielding 2 fragments");
        // Verify the label confirms which tables were removed
        assertTrue(results.stream().anyMatch(f -> f.label().toLowerCase().contains("a")));
        assertTrue(results.stream().anyMatch(f -> f.label().toLowerCase().contains("c")));
    }

    @Test
    void skipsBridgeTableFragment() throws Exception {
        // dept_emp is a bridge — removing it should NOT produce a fragment
        SqlNode node = parse(
            "SELECT e.name, d.name FROM employees e " +
            "INNER JOIN dept_emp de ON e.id = de.emp_id " +
            "INNER JOIN departments d ON d.id = de.dept_id"
        );
        List<DecomposedFragment> results = rule.apply(node, "postgresql");

        assertEquals(2, results.size(), "Bridge table dept_emp skipped, 2 fragments remain");
        // The removed names should include employees and departments, not dept_emp
        boolean hasRemoveEmployees = results.stream().anyMatch(f -> f.label().toLowerCase().contains("employees"));
        boolean hasRemoveDepartments = results.stream().anyMatch(f -> f.label().toLowerCase().contains("departments"));
        boolean hasRemoveDeptEmp = results.stream().anyMatch(f -> f.label().toLowerCase().contains("dept_emp"));
        assertTrue(hasRemoveEmployees, "Should have Remove employees fragment");
        assertTrue(hasRemoveDepartments, "Should have Remove departments fragment");
        assertFalse(hasRemoveDeptEmp, "Should NOT have Remove dept_emp fragment (bridge)");
    }

    @Test
    void splitsCrossJoin() throws Exception {
        SqlNode node = parse(
            "SELECT * FROM users CROSS JOIN orders"
        );
        List<DecomposedFragment> results = rule.apply(node, "postgresql");

        assertEquals(2, results.size());
        assertTrue(results.stream().anyMatch(f -> f.label().startsWith("Remove ")));
    }

    @Test
    void singleTableNoJoin() throws Exception {
        SqlNode node = parse("SELECT * FROM users");
        List<DecomposedFragment> results = rule.apply(node, "postgresql");

        assertTrue(results.isEmpty());
    }

    @Test
    void branchSqlIsStandalone() throws Exception {
        SqlNode node = parse(
            "SELECT * FROM users INNER JOIN orders ON users.id = orders.user_id"
        );
        List<DecomposedFragment> results = rule.apply(node, "postgresql");

        for (DecomposedFragment f : results) {
            assertTrue(f.sqlFragment().startsWith("SELECT "),
                "Each branch should be a SELECT: " + f.sqlFragment());
            assertTrue(f.executable());
            assertFalse(f.sqlFragment().isBlank());
        }
    }

    @Test
    void mysqlDialect() throws Exception {
        SqlNode node = parseMysql(
            "SELECT * FROM users INNER JOIN orders ON users.id = orders.user_id"
        );
        List<DecomposedFragment> results = rule.apply(node, "mysql");

        assertEquals(2, results.size());
    }

    @Test
    void programmaticInnerJoin() {
        SqlNode users = new SqlIdentifier("users", SqlParserPos.ZERO);
        SqlNode orders = new SqlIdentifier("orders", SqlParserPos.ZERO);
        SqlNode condition = SqlNodeBuilder.eqCol("users.id", "orders.user_id");
        SqlJoin join = SqlNodeBuilder.innerJoin(users, orders, condition);

        SqlNode node = new SqlNodeBuilder()
            .selectExpr(SqlIdentifier.star(SqlParserPos.ZERO))
            .from(join)
            .buildSelect();

        List<DecomposedFragment> results = rule.apply(node, "postgresql");

        assertEquals(2, results.size());
        assertTrue(results.stream().allMatch(f -> f.type() == FragmentType.JOIN_SPLIT));
    }

    @Test
    void programmaticLeftJoin() {
        SqlNode users = new SqlIdentifier("users", SqlParserPos.ZERO);
        SqlNode orders = new SqlIdentifier("orders", SqlParserPos.ZERO);
        SqlNode condition = SqlNodeBuilder.eqCol("users.id", "orders.user_id");
        SqlJoin join = SqlNodeBuilder.leftJoin(users, orders, condition);

        SqlNode node = new SqlNodeBuilder()
            .selectExpr(SqlIdentifier.star(SqlParserPos.ZERO))
            .from(join)
            .buildSelect();

        List<DecomposedFragment> results = rule.apply(node, "postgresql");

        assertEquals(2, results.size());
        assertTrue(results.stream().anyMatch(f -> f.label().startsWith("Remove ")));
    }

    @Test
    void programmaticCrossJoin() {
        SqlNode users = new SqlIdentifier("users", SqlParserPos.ZERO);
        SqlNode orders = new SqlIdentifier("orders", SqlParserPos.ZERO);
        SqlJoin join = SqlNodeBuilder.crossJoin(users, orders);

        SqlNode node = new SqlNodeBuilder()
            .selectExpr(SqlIdentifier.star(SqlParserPos.ZERO))
            .from(join)
            .buildSelect();

        List<DecomposedFragment> results = rule.apply(node, "postgresql");

        assertEquals(2, results.size());
    }

    @Test
    void unquotedIdentifiersStayUnquoted() throws Exception {
        // Original unquoted identifiers should stay unquoted in output
        SqlNode node = parse(
            "SELECT u.id, u.name FROM users u INNER JOIN orders o ON u.id = o.user_id"
        );
        List<DecomposedFragment> results = rule.apply(node, "postgresql");

        assertEquals(2, results.size());
        for (DecomposedFragment f : results) {
            String sql = f.sqlFragment();
            // Should NOT have quotes around simple identifiers
            assertFalse(sql.contains("\"users\""), "users should not be quoted: " + sql);
            assertFalse(sql.contains("\"orders\""), "orders should not be quoted: " + sql);
            assertFalse(sql.contains("\"o\""), "alias 'o' should not be quoted: " + sql);
        }
    }

    @Test
    void mysqlIdentifiersQuoted() throws Exception {
        // When MySQL parser returns quoted identifiers (as SqlIdentifier with quotes),
        // NodeToSql should strip unnecessary quotes
        SqlNode node = parseMysql(
            "SELECT * FROM users INNER JOIN orders ON users.id = orders.user_id"
        );
        List<DecomposedFragment> results = rule.apply(node, "mysql");

        assertEquals(2, results.size());
        for (DecomposedFragment f : results) {
            String sql = f.sqlFragment();
            // MySQL output should not have backticks for simple identifiers
            assertFalse(sql.contains("`users`"), "users should not be backtick-quoted: " + sql);
            assertFalse(sql.contains("`orders`"), "orders should not be backtick-quoted: " + sql);
        }
    }

    @Test
    void columnReferencesPreserved() throws Exception {
        // Column references in SELECT and WHERE should preserve original names
        SqlNode node = parse(
            "SELECT users.id, users.name, orders.total FROM users INNER JOIN orders ON users.id = orders.user_id WHERE orders.status = 'active'"
        );
        List<DecomposedFragment> results = rule.apply(node, "postgresql");

        assertEquals(2, results.size());
        // "Remove users" fragment should have orders columns but no users columns
        DecomposedFragment removeUsers = results.stream()
            .filter(f -> f.label().toLowerCase().contains("users"))
            .findFirst().orElseThrow();
        assertTrue(removeUsers.sqlFragment().toLowerCase().contains("orders"),
            "Remove users should have orders: " + removeUsers.sqlFragment());
        assertFalse(removeUsers.sqlFragment().toLowerCase().contains("users.id"),
            "Remove users should not have users.id: " + removeUsers.sqlFragment());
    }

    @Test
    void preservesLeftJoinType() throws Exception {
        // 3-table LEFT JOIN chain: removing rightmost table should keep LEFT JOIN + ON
        SqlNode node = parse(
            "SELECT * FROM a LEFT JOIN b ON a.id = b.a_id LEFT JOIN c ON b.id = c.b_id"
        );
        List<DecomposedFragment> results = rule.apply(node, "postgresql");

        for (DecomposedFragment f : results) {
            String sql = f.sqlFragment().toUpperCase();
            // Should NOT use comma-separated FROM for multi-table fragments
            assertFalse(sql.matches("(?s).*FROM\\s+\\w+\\s*,\\s*\\w+.*"),
                "Should not use comma-separated FROM: " + f.sqlFragment());
        }
    }

    @Test
    void splitsCommaJoin() throws Exception {
        // Comma-separated FROM (t1, t2) should also be splittable
        SqlNode node = parse(
            "SELECT * FROM users, orders"
        );
        List<DecomposedFragment> results = rule.apply(node, "postgresql");

        assertEquals(2, results.size());
        assertTrue(results.stream().anyMatch(f -> f.label().startsWith("Remove ")));
    }

    @Test
    void commaJoinNoWherePreserved() throws Exception {
        // Comma join — each removal produces a fragment
        SqlNode node = parse(
            "SELECT * FROM users, orders, products"
        );
        List<DecomposedFragment> results = rule.apply(node, "postgresql");

        // Each leaf removal should produce valid SQL
        assertTrue(results.size() >= 1, "Should have at least 1 fragment");
        for (DecomposedFragment f : results) {
            assertTrue(f.sqlFragment().startsWith("SELECT "));
            assertTrue(f.executable());
        }
    }

    @Test
    void whereConditionsFilterByRemovedAlias() throws Exception {
        // Regression test: WHERE conditions referencing removed table aliases must be filtered out
        // Bug: filterWhereByAliases was passing remainingAliases to collectAliasRefs,
        // causing removed aliases to not be detected, resulting in invalid SQL
        SqlNode node = parse(
            "SELECT e.name, d.dept_name FROM employees e " +
            "INNER JOIN departments d ON e.dept_id = d.id " +
            "WHERE e.status = 'active' AND d.location = 'NYC'"
        );
        List<DecomposedFragment> results = rule.apply(node, "postgresql");

        assertEquals(2, results.size());

        // Find the fragment that removes "employees"
        DecomposedFragment removeEmployees = results.stream()
            .filter(f -> f.label().toLowerCase().contains("employees"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Expected fragment removing employees"));

        String sql = removeEmployees.sqlFragment();
        // Should NOT contain "e." (employees alias) anywhere
        assertFalse(sql.toUpperCase().contains(" E.") || sql.toUpperCase().contains("E."),
            "Fragment should not reference removed table alias 'e': " + sql);
        // Should contain "d." (departments alias) since it's still present
        assertTrue(sql.toUpperCase().contains(" D.") || sql.toUpperCase().contains("D."),
            "Fragment should still reference remaining table alias 'd': " + sql);

        // Find the fragment that removes "departments"
        DecomposedFragment removeDepartments = results.stream()
            .filter(f -> f.label().toLowerCase().contains("departments"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Expected fragment removing departments"));

        sql = removeDepartments.sqlFragment();
        // Should NOT contain "d." (departments alias) anywhere
        assertFalse(sql.toUpperCase().contains(" D.") || sql.toUpperCase().contains("D."),
            "Fragment should not reference removed table alias 'd': " + sql);
        // Should contain "e." (employees alias) since it's still present
        assertTrue(sql.toUpperCase().contains(" E.") || sql.toUpperCase().contains("E."),
            "Fragment should still reference remaining table alias 'e': " + sql);
    }

    @Test
    void splitPreservesDistinct() throws Exception {
        SqlNode root = parse("SELECT DISTINCT a.x FROM a JOIN b ON a.id = b.a_id");
        List<DecomposedFragment> fragments = rule.apply(root, "postgresql");
        assertFalse(fragments.isEmpty());
        for (DecomposedFragment f : fragments) {
            assertTrue(f.sqlFragment().toUpperCase().contains("DISTINCT"),
                "Fragment lost DISTINCT: " + f.sqlFragment());
            assertDoesNotThrow(() -> SqlParser.create(f.sqlFragment()).parseQuery(),
                "Fragment must stay parseable: " + f.sqlFragment());
        }
    }

    @Test
    void splitPreservesLimitAndOffsetWithoutOrderBy() throws Exception {
        // mysql profile renders SqlOrderBy paging as "LIMIT n OFFSET m"; the postgresql
        // profile emits ANSI "OFFSET n ROWS FETCH NEXT n ROWS ONLY" (see task-16 report).
        SqlNode root = parse("SELECT a.x FROM a JOIN b ON a.id = b.a_id LIMIT 5 OFFSET 2");
        List<DecomposedFragment> fragments = rule.apply(root, "mysql");
        assertFalse(fragments.isEmpty());
        for (DecomposedFragment f : fragments) {
            String upper = f.sqlFragment().toUpperCase();
            assertTrue(upper.contains("LIMIT 5"), "Fragment lost LIMIT: " + f.sqlFragment());
            assertTrue(upper.contains("OFFSET 2"), "Fragment lost OFFSET: " + f.sqlFragment());
            assertDoesNotThrow(() -> SqlParser.create(f.sqlFragment()).parseQuery(),
                "Fragment must stay parseable: " + f.sqlFragment());
        }
    }

    @Test
    void splitFiltersGroupByAndOrderByOfRemovedTable() throws Exception {
        SqlNode root = parse(
            "SELECT a.x, COUNT(*) FROM a JOIN b ON a.id = b.a_id GROUP BY a.x, b.y ORDER BY b.y");
        List<DecomposedFragment> fragments = rule.apply(root, "postgresql");
        DecomposedFragment removeB = fragments.stream()
            .filter(f -> f.label().toUpperCase().startsWith("REMOVE B"))
            .findFirst().orElseThrow();
        String upper = removeB.sqlFragment().toUpperCase();
        assertTrue(upper.contains("GROUP BY"), "GROUP BY must survive: " + removeB.sqlFragment());
        assertTrue(upper.contains("A.X"), "Retained GROUP BY item lost: " + removeB.sqlFragment());
        assertFalse(upper.contains("B.Y"),
            "Items referencing the removed table must be filtered: " + removeB.sqlFragment());
        assertDoesNotThrow(() -> SqlParser.create(removeB.sqlFragment()).parseQuery(),
            "Fragment must stay parseable: " + removeB.sqlFragment());
    }

    @Test
    void splitDropsGroupByEntirelyWhenAllItemsReferenceRemovedTable() throws Exception {
        SqlNode root = parse(
            "SELECT b.y, COUNT(*) FROM a JOIN b ON a.id = b.a_id GROUP BY b.y");
        List<DecomposedFragment> fragments = rule.apply(root, "postgresql");
        DecomposedFragment removeB = fragments.stream()
            .filter(f -> f.label().toUpperCase().startsWith("REMOVE B"))
            .findFirst().orElseThrow();
        String upper = removeB.sqlFragment().toUpperCase();
        assertFalse(upper.contains("GROUP BY"),
            "GROUP BY must be dropped entirely when all its items reference the removed table: "
                + removeB.sqlFragment());
        assertDoesNotThrow(() -> SqlParser.create(removeB.sqlFragment()).parseQuery(),
            "Fragment must stay parseable: " + removeB.sqlFragment());
    }

    @Test
    void splitCleansOnConditionOfSurvivingJoin() throws Exception {
        SqlNode root = parse(
            "SELECT a.x FROM a JOIN b ON a.id = b.a_id JOIN c ON c.a_id = a.id AND c.b_id = b.id");
        List<DecomposedFragment> fragments = rule.apply(root, "postgresql");
        DecomposedFragment removeB = fragments.stream()
            .filter(f -> f.label().toUpperCase().startsWith("REMOVE B"))
            .findFirst().orElseThrow();
        String upper = removeB.sqlFragment().toUpperCase();
        assertTrue(upper.contains("C.A_ID = A.ID"),
            "Surviving part of the ON condition lost: " + removeB.sqlFragment());
        assertFalse(upper.contains("C.B_ID"),
            "ON conjunct referencing the removed table must be cleaned: " + removeB.sqlFragment());
        assertDoesNotThrow(() -> SqlParser.create(removeB.sqlFragment()).parseQuery(),
            "Fragment must stay parseable: " + removeB.sqlFragment());
    }

    @Test
    void splitReportsUnprovenBareColumnOwnership() throws Exception {
        SqlNode root = parse("SELECT x, a.y FROM a JOIN b ON a.id = b.a_id");
        RuleEvaluation result = rule.evaluate(root, "postgresql");
        assertTrue(result.fragments().isEmpty());
        assertEquals(2, result.issues().size());
        assertTrue(result.issues().stream().allMatch(i ->
            i.code().equals("UNRESOLVED_DEPENDENCY") && i.sql() != null && i.scopePath() != null));
        assertThrows(IllegalStateException.class, () -> rule.apply(root, "postgresql"));
    }
}
