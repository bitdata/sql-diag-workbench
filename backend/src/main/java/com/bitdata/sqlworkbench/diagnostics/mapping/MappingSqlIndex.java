package com.bitdata.sqlworkbench.diagnostics.mapping;

import com.bitdata.sqlworkbench.sql.SqlDialectResolver;
import com.bitdata.sqlworkbench.sql.model.SqlPosition;
import org.apache.calcite.sql.*;
import org.apache.calcite.sql.parser.SqlParser;

import java.util.ArrayList;
import java.util.List;

/** Relation occurrences, rather than substring matches in arbitrary SQL expressions. */
final class MappingSqlIndex {
    record Relation(String name, String alias, PlanSqlMapping.SqlSpan span) {
        boolean matches(String planName) {
            return planName != null && (same(name, planName) || same(alias, planName));
        }
    }
    private final List<Relation> relations = new ArrayList<>();

    static MappingSqlIndex parse(String sql, String dialect, SqlDialectResolver dialects) throws Exception {
        var index = new MappingSqlIndex();
        index.visit(SqlParser.create(sql, dialects.parserConfig(dialect)).parseQuery(), sql);
        return index;
    }

    List<Relation> candidates(String table) {
        return relations.stream().filter(r -> r.matches(table)).toList();
    }

    private void visit(SqlNode node, String sql) {
        if (node == null) return;
        if (node instanceof SqlSelect select) {
            from(select.getFrom(), sql, null);
            for (var operand : select.getOperandList()) {
                if (operand != select.getFrom()) visit(operand, sql);
            }
        } else if (node instanceof SqlWith with) {
            for (var item : with.withList) visit(((SqlWithItem) item).query, sql);
            visit(with.body, sql);
        } else if (node instanceof SqlNodeList list) {
            for (var child : list) visit(child, sql);
        } else if (node instanceof SqlCall call) {
            for (var child : call.getOperandList()) visit(child, sql);
        }
    }

    private void from(SqlNode node, String sql, String alias) {
        if (node == null) return;
        if (node instanceof SqlIdentifier id) {
            var pos = id.getParserPosition();
            int start = SqlPosition.fromLineColumn(pos.getLineNum(), pos.getColumnNum(), sql).offset();
            int end = SqlPosition.fromLineColumn(pos.getEndLineNum(), pos.getEndColumnNum() + 1, sql).offset();
            relations.add(new Relation(String.join(".", id.names), alias,
                new PlanSqlMapping.SqlSpan("relation_" + relations.size(), "TABLE_REF", sql.substring(start, end), start, end)));
        } else if (node instanceof SqlJoin join) {
            from(join.getLeft(), sql, null);
            from(join.getRight(), sql, null);
            visit(join.getCondition(), sql);
        } else if (node instanceof SqlCall call && call.getKind() == SqlKind.AS) {
            from(call.operand(0), sql, call.operand(1).toString());
        } else {
            visit(node, sql);
        }
    }

    private static boolean same(String left, String right) {
        if (left == null || right == null) return false;
        // Schema qualification is retained when both sides provide it. A missing
        // schema/alias is only candidate evidence and never establishes EXACT.
        String a = left.replace("`", "").replace("\"", "");
        String b = right.replace("`", "").replace("\"", "");
        if (!a.contains(".") || !b.contains(".")) {
            a = a.substring(a.lastIndexOf('.') + 1);
            b = b.substring(b.lastIndexOf('.') + 1);
        }
        return a.equalsIgnoreCase(b);
    }
}
