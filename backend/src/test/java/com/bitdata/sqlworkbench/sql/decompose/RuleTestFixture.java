package com.bitdata.sqlworkbench.sql.decompose;

import org.apache.calcite.sql.*;
import org.apache.calcite.sql.dialect.MysqlSqlDialect;
import org.apache.calcite.sql.dialect.PostgresqlSqlDialect;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.sql.parser.SqlParser;
import org.junit.jupiter.api.BeforeEach;

import java.util.ArrayList;
import java.util.List;

/**
 * Base fixture for decomposition rule tests.
 * Provides both parser-based and programmatic AST fixtures.
 */
public abstract class RuleTestFixture {

    protected SqlParser.Config pgConfig = SqlParser.config()
        .withCaseSensitive(true);

    protected SqlParser.Config mysqlConfig = SqlParser.config()
        .withCaseSensitive(false);

    protected SqlDialect pgDialect = PostgresqlSqlDialect.DEFAULT;
    protected SqlDialect mysqlDialect = MysqlSqlDialect.DEFAULT;

    // ---- Parser-based fixtures ----

    protected SqlSelect simpleSelect;
    protected SqlSelect selectWithWhere;
    protected SqlSelect selectWithOrderBy;
    protected SqlSelect selectWithLimit;

    // ---- Programmatic AST fixtures (parser-free) ----

    protected SqlSelect builtSimpleSelect;
    protected SqlSelect builtSelectWithWhere;
    protected SqlOrderBy builtSelectWithOrderBy;
    protected SqlOrderBy builtSelectWithLimit;

    @BeforeEach
    void parseFixtures() throws Exception {
        // Parser-based
        simpleSelect = parseSelect(
            "SELECT id, name FROM users"
        );

        selectWithWhere = parseSelect(
            "SELECT id, name FROM users WHERE status = 'active' AND email IS NOT NULL"
        );

        selectWithOrderBy = parseSelect(
            "SELECT id, name FROM users ORDER BY created_at DESC"
        );

        selectWithLimit = parseSelect(
            "SELECT id, name FROM users ORDER BY id LIMIT 10 OFFSET 5"
        );

        // Programmatic AST (no parser dependency)
        builtSimpleSelect = SqlNodeBuilder.simpleSelect("id", "name");

        builtSelectWithWhere = new SqlNodeBuilder()
            .select("id", "name")
            .from("users")
            .where(b -> b.and(
                b2 -> b2.eq("status", "active").build(),
                b2 -> b2.isNotNull("email").build()).build())
            .buildSelect();

        builtSelectWithOrderBy = new SqlNodeBuilder()
            .select("id", "name")
            .from("users")
            .orderBy(b -> b.desc("created_at").build())
            .buildOrderBy();

        builtSelectWithLimit = new SqlNodeBuilder()
            .select("id", "name")
            .from("users")
            .orderBy(b -> b.asc("id").build())
            .limit(10)
            .offset(5)
            .buildOrderBy();
    }

    protected SqlSelect parseSelect(String sql) throws Exception {
        SqlParser parser = SqlParser.create(sql, pgConfig);
        SqlNode node = parser.parseQuery();
        if (node instanceof SqlOrderBy ob && ob.query instanceof SqlSelect select) {
            return select;
        }
        if (node instanceof SqlSelect select) {
            return select;
        }
        throw new IllegalArgumentException("Not a SELECT: " + sql);
    }

    protected SqlNode parse(String sql) throws Exception {
        SqlParser parser = SqlParser.create(sql, pgConfig);
        return parser.parseQuery();
    }

    protected SqlNode parseMysql(String sql) throws Exception {
        SqlParser parser = SqlParser.create(sql, mysqlConfig);
        return parser.parseQuery();
    }

    protected List<SqlNode> flattenAnd(SqlNode where) {
        List<SqlNode> result = new ArrayList<>();
        flattenAndRecursive(where, result);
        return result;
    }

    private void flattenAndRecursive(SqlNode node, List<SqlNode> result) {
        if (node instanceof SqlBasicCall call
            && call.getOperator() == SqlStdOperatorTable.AND) {
            for (SqlNode operand : call.getOperandList()) {
                flattenAndRecursive(operand, result);
            }
        } else {
            result.add(node);
        }
    }
}