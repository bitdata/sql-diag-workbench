package com.bitdata.sqlworkbench.sql.decompose;

import com.bitdata.sqlworkbench.sql.DialectProfile;
import com.bitdata.sqlworkbench.sql.NodeToSql;
import com.bitdata.sqlworkbench.sql.model.WhereInfo;

import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlNodeList;
import org.apache.calcite.sql.SqlOrderBy;
import org.apache.calcite.sql.SqlSelect;
import org.apache.calcite.sql.SqlWith;
import org.apache.calcite.sql.SqlBasicCall;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Read-only utilities for inspecting the WHERE clause of a SQL fragment.
 *
 * <p>Used by the Add-WHERE composition feature to compute the set
 * difference between a parent fragment (more WHERE conditions) and a
 * child fragment (the result of {@code PredicateDecompositionRule}
 * which isolates one condition, or {@code ClauseStrippingRule} which
 * removes WHERE entirely).
 *
 * <p>Supports the same wrapping patterns as
 * {@code JoinInfoExtractor}: SELECT with optional {@link SqlOrderBy} /
 * {@link SqlWith} wrappers.
 */
public final class WhereInfoExtractor {

    private WhereInfoExtractor() {}

    /**
     * Extract every AND-connected top-level condition in the WHERE
     * clause, in source order. Returns an empty list if the fragment
     * has no WHERE; a single non-AND condition → returns a one-element
     * list.
     */
    public static List<WhereInfo> extractWhereConditions(SqlNode root, DialectProfile profile) {
        if (root == null) return List.of();
        SqlNode inner = root;
        if (inner instanceof SqlOrderBy ob && ob.query != null) inner = ob.query;
        if (inner instanceof SqlWith with && with.body != null) inner = with.body;
        if (!(inner instanceof SqlSelect select)) return List.of();

        SqlNode where = select.getWhere();
        if (where == null) return List.of();

        // Flatten AND tree — same shape as PredicateDecompositionRule.
        // Note: a single non-AND condition is extracted too (one-element list),
        // so diff against a STRIPPED child or an isolated single predicate
        // attributes the parent's added condition correctly.
        List<SqlNode> flattened = new ArrayList<>();
        flattenAnd(where, flattened);

        List<WhereInfo> result = new ArrayList<>(flattened.size());
        for (int i = 0; i < flattened.size(); i++) {
            String sql = conditionToString(flattened.get(i), profile);
            result.add(new WhereInfo(sql, i));
        }
        return result;
    }

    /**
     * Set difference: conditions present in {@code parent} but absent
     * in {@code child}. Equality uses {@link WhereInfo#identityKey()}.
     */
    public static List<WhereInfo> diffWhereConditions(
        List<WhereInfo> parent,
        List<WhereInfo> child
    ) {
        if (parent == null || parent.isEmpty()) return List.of();
        if (child == null) child = List.of();
        Set<String> childKeys = new LinkedHashSet<>();
        for (WhereInfo w : child) childKeys.add(w.identityKey());
        List<WhereInfo> diff = new ArrayList<>();
        for (WhereInfo p : parent) {
            if (!childKeys.contains(p.identityKey())) diff.add(p);
        }
        return diff;
    }

    // ── AST walking (mirrors PredicateDecompositionRule.flattenAnd) ─────

    private static void flattenAnd(SqlNode node, List<SqlNode> out) {
        if (node == null) return;
        if (node instanceof SqlBasicCall call
            && call.getOperator() == SqlStdOperatorTable.AND) {
            for (SqlNode operand : call.getOperandList()) {
                flattenAnd(operand, out);
            }
        } else {
            out.add(node);
        }
    }

    private static String conditionToString(SqlNode condition, DialectProfile profile) {
        if (condition == null) return "";
        try {
            return NodeToSql.toSql(condition, profile);
        } catch (Exception e) {
            throw new IllegalStateException("SQL serialization failed", e);
        }
    }
}
