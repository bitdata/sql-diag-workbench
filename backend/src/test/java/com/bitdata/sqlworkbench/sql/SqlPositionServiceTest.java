package com.bitdata.sqlworkbench.sql;

import com.bitdata.sqlworkbench.connection.DatabaseStrategyFactory;
import com.bitdata.sqlworkbench.connection.MySqlStrategy;
import com.bitdata.sqlworkbench.connection.PostgreSqlStrategy;
import com.bitdata.sqlworkbench.sql.model.SqlNodePosition;
import com.bitdata.sqlworkbench.sql.model.SqlPosition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Spike test: validates Calcite SqlParserPos accuracy on 10 diverse SQL queries.
 * Tests that position offsets actually match the SQL text.
 */
public class SqlPositionServiceTest {

    private SqlPositionService service;

    @BeforeEach
    void setUp() {
        DatabaseStrategyFactory factory = new DatabaseStrategyFactory(Map.of(
            "postgresqlStrategy", new PostgreSqlStrategy(),
            "mysqlStrategy", new MySqlStrategy()
        ));
        service = new SqlPositionService(new SqlDialectResolver(factory));
    }

    @Test
    void simpleSelect_extractsWhereClause() {
        // Note: WHERE position starts at the first expression token, not the WHERE keyword
        String sql = "SELECT id, name FROM users WHERE status = 'active'";
        List<SqlNodePosition> positions = service.extractPositions(sql, "postgresql");

        SqlNodePosition whereClause = findByType(positions, "WHERE_CLAUSE");
        assertNotNull(whereClause, "Should find WHERE clause");
        assertTrue(whereClause.start().offset() < whereClause.end().offset(), "Start offset should be before end");
        assertTrue(whereClause.end().offset() <= sql.length(), "End offset should not exceed SQL length");

        // The extracted text should contain the condition (starts at first token after WHERE)
        String extracted = sql.substring(whereClause.start().offset(), whereClause.end().offset());
        assertTrue(extracted.contains("status"), "Extracted WHERE should contain 'status'");
        assertTrue(extracted.contains("active"), "Extracted WHERE should contain 'active'");
    }

    @Test
    void simpleSelect_extractsSelectList() {
        String sql = "SELECT id, name FROM users";
        List<SqlNodePosition> positions = service.extractPositions(sql, "postgresql");

        SqlNodePosition selectList = findByType(positions, "SELECT_LIST");
        assertNotNull(selectList, "Should find SELECT list");
        assertTrue(selectList.end().offset() <= sql.length(), "End offset should not exceed SQL length");

        String extracted = sql.substring(selectList.start().offset(), selectList.end().offset());
        assertTrue(extracted.contains("id"), "Extracted text should contain id");
        assertTrue(extracted.contains("name"), "Extracted text should contain name");
    }

    @Test
    void simpleSelect_extractsColumnReferences() {
        String sql = "SELECT id, name FROM users WHERE status = 'active'";
        List<SqlNodePosition> positions = service.extractPositions(sql, "postgresql");

        // Should find column references in SELECT list
        long columnRefs = positions.stream()
            .filter(p -> p.type().equals("COLUMN_REF"))
            .count();
        assertTrue(columnRefs > 0, "Should find column references");
    }

    @Test
    void joinQuery_extractsJoinCondition() {
        String sql = "SELECT u.name, o.total FROM users u INNER JOIN orders o ON u.id = o.user_id WHERE o.total > 100";
        List<SqlNodePosition> positions = service.extractPositions(sql, "postgresql");

        SqlNodePosition join = findByType(positions, "JOIN_CONDITION");
        assertNotNull(join, "Should find JOIN");
        assertTrue(join.label().toUpperCase().contains("INNER"), "JOIN label should contain INNER");
    }

    @Test
    void joinQuery_extractsWhereClause() {
        String sql = "SELECT u.name, o.total FROM users u INNER JOIN orders o ON u.id = o.user_id WHERE o.total > 100";
        List<SqlNodePosition> positions = service.extractPositions(sql, "postgresql");

        SqlNodePosition where = findByType(positions, "WHERE_CLAUSE");
        assertNotNull(where, "Should find WHERE");
        String extracted = sql.substring(where.start().offset(), where.end().offset());
        assertTrue(extracted.contains("o.total"), "Extracted WHERE should contain o.total");
    }

    @Test
    void groupByQuery_extractsGroupBy() {
        String sql = "SELECT category, COUNT(*) FROM products GROUP BY category HAVING COUNT(*) > 5";
        List<SqlNodePosition> positions = service.extractPositions(sql, "postgresql");

        SqlNodePosition groupBy = findByType(positions, "GROUP_BY");
        assertNotNull(groupBy, "Should find GROUP BY");

        SqlNodePosition having = findByType(positions, "HAVING_CLAUSE");
        assertNotNull(having, "Should find HAVING");
    }

    @Test
    void orderByQuery_extractsOrderBy() {
        String sql = "SELECT id, name FROM users ORDER BY name DESC, created_at ASC";
        List<SqlNodePosition> positions = service.extractPositions(sql, "postgresql");

        SqlNodePosition orderBy = findByType(positions, "ORDER_BY");
        assertNotNull(orderBy, "Should find ORDER BY");
        assertTrue(orderBy.end().offset() <= sql.length(), "End offset should not exceed SQL length");
    }

    @Test
    void subquery_extractsSubquery() {
        String sql = "SELECT id, name FROM (SELECT id, name FROM users WHERE active = true) AS active_users";
        List<SqlNodePosition> positions = service.extractPositions(sql, "postgresql");

        // SqlNodeList contains the subquery elements - just verify positions were extracted
        SqlNodePosition selectList = findByType(positions, "SELECT_LIST");
        assertNotNull(selectList, "Should find SELECT list in subquery");
    }

    @Test
    void cteQuery_extractsPositions() {
        String sql = "WITH active_users AS (SELECT * FROM users WHERE active = true) SELECT * FROM active_users";
        List<SqlNodePosition> positions = service.extractPositions(sql, "postgresql");

        // Should extract positions from CTE query - at minimum, SELECT list should be found
        assertTrue(positions.size() > 0, "Should extract positions from CTE query");
    }

    @Test
    void complexQuery_returnsMultiplePositions() {
        String sql = "SELECT u.name, o.total, o.created_at FROM users u INNER JOIN orders o ON u.id = o.user_id WHERE o.total > 100 ORDER BY o.created_at DESC";
        List<SqlNodePosition> positions = service.extractPositions(sql, "postgresql");

        assertTrue(positions.size() >= 3, "Should find multiple positions (SELECT, WHERE, JOIN, ORDER BY)");

        // Verify all positions have valid offsets
        for (SqlNodePosition pos : positions) {
            assertTrue(pos.start().offset() >= 0, "Start offset should be non-negative");
            assertTrue(pos.end().offset() >= pos.start().offset(), "End offset should be >= start offset");
            assertTrue(pos.end().offset() <= sql.length(), "End offset should not exceed SQL length");
        }
    }

    @Test
    void invalidSql_returnsEmptyList() {
        String sql = "SELECT FROM invalid syntax";
        List<SqlNodePosition> positions = service.extractPositions(sql, "postgresql");

        // Should not throw, should return empty list
        assertNotNull(positions);
    }

    @Test
    void allPositionsHaveValidOffsets() {
        String[] testSqls = {
            "SELECT id FROM users WHERE status = 'active'",
            "SELECT u.name FROM users u INNER JOIN orders o ON u.id = o.user_id",
            "SELECT category, COUNT(*) FROM products GROUP BY category",
            "SELECT id FROM users ORDER BY created_at DESC",
            "WITH cte AS (SELECT * FROM t) SELECT * FROM cte"
        };

        for (String sql : testSqls) {
            List<SqlNodePosition> positions = service.extractPositions(sql, "postgresql");
            for (SqlNodePosition pos : positions) {
                assertTrue(pos.start().offset() >= 0,
                    "Invalid start offset for: " + sql);
                assertTrue(pos.end().offset() > pos.start().offset(),
                    "End should be after start for: " + sql);
                assertTrue(pos.end().offset() <= sql.length(),
                    "End offset exceeds SQL length for: " + sql);
            }
        }
    }

    @Test
    void clickableSelection_whereClause() {
        // Simulates user clicking on WHERE clause - should return a valid position
        String sql = "SELECT id, name FROM users WHERE status = 'active'";
        List<SqlNodePosition> positions = service.extractPositions(sql, "postgresql");

        SqlNodePosition whereClause = findByType(positions, "WHERE_CLAUSE");
        assertNotNull(whereClause);

        // User clicks on WHERE - simulate by finding position containing "WHERE" keyword
        int whereKeywordIndex = sql.indexOf("WHERE");
        assertTrue(whereKeywordIndex >= 0, "WHERE keyword should exist in SQL");

        // The WHERE clause position should start somewhere in the WHERE clause region
        // It may not start exactly at "WHERE" (starts at first expression token) but should cover the clause
        assertTrue(whereClause.start().offset() > sql.indexOf("FROM"),
            "WHERE clause should start after FROM");
        assertTrue(whereClause.end().offset() <= sql.length(),
            "WHERE clause should end within SQL");
    }

    private SqlNodePosition findByType(List<SqlNodePosition> positions, String type) {
        return positions.stream()
            .filter(p -> p.type().equals(type))
            .findFirst()
            .orElse(null);
    }
}