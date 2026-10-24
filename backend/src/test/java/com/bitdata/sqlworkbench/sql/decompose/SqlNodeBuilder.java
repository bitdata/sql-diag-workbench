package com.bitdata.sqlworkbench.sql.decompose;

import org.apache.calcite.sql.*;
import org.apache.calcite.sql.dialect.PostgresqlSqlDialect;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.sql.parser.SqlParserPos;

import java.util.ArrayList;
import java.util.List;

/**
 * Programmatic SqlNode AST builder for decomposition tests.
 *
 * Constructs Calcite AST nodes directly via the Calcite API so decomposition
 * tests don't depend on the SQL parser. This allows testing with edge-case
 * AST shapes that are hard to express in SQL text.
 *
 * Usage:
 * <pre>{@code
 *   SqlNode node = new SqlNodeBuilder()
 *       .select("id", "name")
 *       .from("users")
 *       .where(b -> b.and(
 *           b2 -> b2.eq("status", "active").build(),
 *           b2 -> b2.isNotNull("email").build()).build())
 *       .orderBy(b -> b.desc("created_at").build())
 *       .buildOrderBy();
 * }</pre>
 */
public class SqlNodeBuilder {

    private static final SqlParserPos POS = SqlParserPos.ZERO;

    // SELECT clause state
    private SqlNodeList selectList;
    private SqlNode from;
    private SqlNode where;
    private SqlNodeList groupBy;
    private SqlNode having;
    private SqlNodeList orderList;
    private SqlNode offset;
    private SqlNode fetch;
    private SqlNodeList windowList;

    // For set operations (UNION, etc.)
    private SqlNode setOpLeft;
    private SqlNode setOpRight;
    private String setOpName;

    // CTE state
    private SqlNodeList withList;
    private SqlNode withBody;

    /** Start a SELECT with the given column names. */
    public SqlNodeBuilder select(String... columns) {
        List<SqlNode> items = new ArrayList<>();
        for (String col : columns) {
            items.add(new SqlIdentifier(col, POS));
        }
        this.selectList = new SqlNodeList(items, POS);
        return this;
    }

    /** Start a SELECT with raw SqlNode expressions. */
    public SqlNodeBuilder selectExpr(SqlNode... exprs) {
        this.selectList = new SqlNodeList(List.of(exprs), POS);
        return this;
    }

    /** Set the FROM clause (table name). */
    public SqlNodeBuilder from(String table) {
        this.from = new SqlIdentifier(table, POS);
        return this;
    }

    /** Set the FROM clause (raw SqlNode). */
    public SqlNodeBuilder from(SqlNode node) {
        this.from = node;
        return this;
    }

    /** Set the WHERE clause using a condition builder function. */
    public SqlNodeBuilder where(ConditionFn fn) {
        this.where = fn.apply(new ConditionBuilder());
        return this;
    }

    /** Set the WHERE clause directly from a raw SqlNode. */
    public SqlNodeBuilder whereNode(SqlNode node) {
        this.where = node;
        return this;
    }

    /** Set GROUP BY columns. */
    public SqlNodeBuilder groupBy(String... columns) {
        List<SqlNode> items = new ArrayList<>();
        for (String col : columns) {
            items.add(new SqlIdentifier(col, POS));
        }
        this.groupBy = new SqlNodeList(items, POS);
        return this;
    }

    /** Set HAVING using a condition builder function. */
    public SqlNodeBuilder having(ConditionFn fn) {
        this.having = fn.apply(new ConditionBuilder());
        return this;
    }

    /** Set HAVING directly. */
    public SqlNodeBuilder havingNode(SqlNode node) {
        this.having = node;
        return this;
    }

    /** Set ORDER BY using an order builder function. */
    public SqlNodeBuilder orderBy(OrderFn fn) {
        this.orderList = fn.apply(new OrderBuilder());
        return this;
    }

    /** Set ORDER BY list directly. */
    public SqlNodeBuilder orderList(SqlNodeList list) {
        this.orderList = list;
        return this;
    }

    /** Set LIMIT. */
    public SqlNodeBuilder limit(int n) {
        this.fetch = SqlLiteral.createExactNumeric(String.valueOf(n), POS);
        return this;
    }

    /** Set OFFSET. */
    public SqlNodeBuilder offset(int n) {
        this.offset = SqlLiteral.createExactNumeric(String.valueOf(n), POS);
        return this;
    }

    /** Mark this as a set operation (UNION, UNION ALL, INTERSECT, EXCEPT). */
    public SqlNodeBuilder setOp(String op, SqlNode left, SqlNode right) {
        this.setOpName = op;
        this.setOpLeft = left;
        this.setOpRight = right;
        return this;
    }

    /** Add a CTE: WITH alias AS (query). */
    public SqlNodeBuilder with(String alias, SqlNode cteQuery) {
        if (this.withList == null) {
            this.withList = new SqlNodeList(POS);
        }
        SqlWithItem item = new SqlWithItem(POS,
            new SqlIdentifier(alias, POS), null, cteQuery);
        this.withList.add(item);
        return this;
    }

    /** Set the CTE body query. */
    public SqlNodeBuilder withBody(SqlNode body) {
        this.withBody = body;
        return this;
    }

    /** Build as SqlSelect (no ORDER BY wrapper). */
    public SqlSelect buildSelect() {
        return new SqlSelect(POS, null,
            selectList, from, where, groupBy, having,
            windowList, null, null, null, null);
    }

    /** Build as SqlOrderBy wrapping a SqlSelect (for queries with ORDER BY / LIMIT / OFFSET). */
    public SqlOrderBy buildOrderBy() {
        SqlSelect inner = new SqlSelect(POS, null,
            selectList, from, where, groupBy, having,
            windowList, orderList, offset, fetch, null);
        return new SqlOrderBy(POS, inner,
            orderList != null ? orderList : SqlNodeList.EMPTY,
            offset, fetch);
    }

    /** Build as SqlBasicCall for set operations. */
    public SqlBasicCall buildSetOp() {
        SqlOperator op = switch (setOpName.toUpperCase()) {
            case "UNION" -> SqlStdOperatorTable.UNION;
            case "UNION_ALL" -> SqlStdOperatorTable.UNION_ALL;
            case "INTERSECT" -> SqlStdOperatorTable.INTERSECT;
            case "EXCEPT" -> SqlStdOperatorTable.EXCEPT;
            default -> throw new IllegalArgumentException("Unknown set op: " + setOpName);
        };
        return new SqlBasicCall(op,
            new SqlNode[]{setOpLeft, setOpRight}, POS);
    }

    /** Build as SqlWith (CTE). */
    public SqlWith buildWith() {
        return new SqlWith(POS, withList, withBody);
    }

    // ---- Static convenience builders ----

    /** Simple SELECT with no clauses beyond SELECT + FROM. */
    public static SqlSelect simpleSelect(String... columns) {
        return new SqlNodeBuilder().select(columns).from("t").buildSelect();
    }

    /** SELECT with a WHERE clause. */
    public static SqlSelect selectWhere(ConditionFn fn) {
        return new SqlNodeBuilder().select("id", "name").from("t")
            .where(fn).buildSelect();
    }

    // ---- Functional interfaces for lambda-friendly DSL ----

    @FunctionalInterface
    public interface ConditionFn {
        SqlNode apply(ConditionBuilder b);
    }

    @FunctionalInterface
    public interface OrderFn {
        SqlNodeList apply(OrderBuilder b);
    }

    // ---- Condition DSL ----

    /** Fluent condition builder for WHERE and HAVING clauses. */
    public static class ConditionBuilder {
        private SqlNode node;

        public ConditionBuilder eq(String col, String val) {
            this.node = call(SqlStdOperatorTable.EQUALS,
                new SqlIdentifier(col, POS),
                SqlLiteral.createCharString(val, POS));
            return this;
        }

        public ConditionBuilder eq(String col, int val) {
            this.node = call(SqlStdOperatorTable.EQUALS,
                new SqlIdentifier(col, POS),
                SqlLiteral.createExactNumeric(String.valueOf(val), POS));
            return this;
        }

        public ConditionBuilder ne(String col, String val) {
            this.node = call(SqlStdOperatorTable.NOT_EQUALS,
                new SqlIdentifier(col, POS),
                SqlLiteral.createCharString(val, POS));
            return this;
        }

        public ConditionBuilder gt(String col, int val) {
            this.node = call(SqlStdOperatorTable.GREATER_THAN,
                new SqlIdentifier(col, POS),
                SqlLiteral.createExactNumeric(String.valueOf(val), POS));
            return this;
        }

        public ConditionBuilder lt(String col, int val) {
            this.node = call(SqlStdOperatorTable.LESS_THAN,
                new SqlIdentifier(col, POS),
                SqlLiteral.createExactNumeric(String.valueOf(val), POS));
            return this;
        }

        public ConditionBuilder isNotNull(String col) {
            this.node = call(SqlStdOperatorTable.IS_NOT_NULL,
                new SqlIdentifier(col, POS));
            return this;
        }

        public ConditionBuilder isNull(String col) {
            this.node = call(SqlStdOperatorTable.IS_NULL,
                new SqlIdentifier(col, POS));
            return this;
        }

        /** AND two or more conditions together. Each argument builds its own condition. */
        public ConditionBuilder and(ConditionFn... conditions) {
            SqlNode result = null;
            for (ConditionFn fn : conditions) {
                SqlNode next = fn.apply(new ConditionBuilder());
                result = result == null ? next
                    : new SqlBasicCall(SqlStdOperatorTable.AND,
                        new SqlNode[]{result, next}, POS);
            }
            this.node = result;
            return this;
        }

        /** OR two conditions. */
        public ConditionBuilder or(ConditionFn a, ConditionFn b) {
            this.node = new SqlBasicCall(SqlStdOperatorTable.OR,
                new SqlNode[]{a.apply(new ConditionBuilder()),
                              b.apply(new ConditionBuilder())}, POS);
            return this;
        }

        /** Produce the SqlNode from this builder. */
        public SqlNode build() {
            return node;
        }
    }

    // ---- Order DSL ----

    /** Fluent ORDER BY builder. */
    public static class OrderBuilder {
        private final List<SqlNode> items = new ArrayList<>();

        /** Ascending — bare identifier (no ASC wrapper needed in Calcite). */
        public OrderBuilder asc(String col) {
            items.add(new SqlIdentifier(col, POS));
            return this;
        }

        public OrderBuilder desc(String col) {
            items.add(new SqlBasicCall(SqlStdOperatorTable.DESC,
                new SqlNode[]{new SqlIdentifier(col, POS)}, POS));
            return this;
        }

        public SqlNodeList build() {
            return new SqlNodeList(items, POS);
        }
    }

    // ---- Internal helpers ----

    private static SqlBasicCall call(SqlOperator op, SqlNode... operands) {
        return new SqlBasicCall(op, operands, POS);
    }

    /** Convert a SqlNode back to SQL string for assertions/debugging. */
    public static String toSql(SqlNode node) {
        return node.toSqlString(PostgresqlSqlDialect.DEFAULT).getSql();
    }

    // ---- Join factory methods ----

    public static SqlJoin innerJoin(SqlNode left, SqlNode right, SqlNode condition) {
        return new SqlJoin(POS, left,
            SqlLiteral.createBoolean(false, POS),
            JoinType.INNER.symbol(POS),
            right,
            JoinConditionType.ON.symbol(POS),
            condition);
    }

    public static SqlJoin leftJoin(SqlNode left, SqlNode right, SqlNode condition) {
        return new SqlJoin(POS, left,
            SqlLiteral.createBoolean(false, POS),
            JoinType.LEFT.symbol(POS),
            right,
            JoinConditionType.ON.symbol(POS),
            condition);
    }

    public static SqlJoin crossJoin(SqlNode left, SqlNode right) {
        return new SqlJoin(POS, left,
            SqlLiteral.createBoolean(false, POS),
            JoinType.CROSS.symbol(POS),
            right,
            JoinConditionType.NONE.symbol(POS),
            null);
    }

    /** Build an equality condition between two columns (for join ON clauses). */
    public static SqlBasicCall eqCol(String leftCol, String rightCol) {
        return new SqlBasicCall(SqlStdOperatorTable.EQUALS,
            new SqlNode[]{new SqlIdentifier(leftCol, POS), new SqlIdentifier(rightCol, POS)}, POS);
    }
}
