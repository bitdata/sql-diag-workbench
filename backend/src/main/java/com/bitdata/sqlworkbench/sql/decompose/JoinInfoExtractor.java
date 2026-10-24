package com.bitdata.sqlworkbench.sql.decompose;

import com.bitdata.sqlworkbench.sql.DialectProfile;
import com.bitdata.sqlworkbench.sql.NodeToSql;
import com.bitdata.sqlworkbench.sql.model.JoinInfo;

import org.apache.calcite.sql.JoinType;
import org.apache.calcite.sql.SqlBasicCall;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlJoin;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlOrderBy;
import org.apache.calcite.sql.SqlSelect;
import org.apache.calcite.sql.SqlWith;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.sql.parser.SqlParserPos;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Read-only utilities for inspecting the JOIN structure of a SQL fragment.
 *
 * <p>Used by the Add-Join composition feature to compute the set difference
 * between a parent fragment (more JOINs) and its child fragment (fewer JOINs)
 * produced by {@link JoinSplitRule}.
 *
 * <p>Supports the same SQL shapes as {@code JoinSplitRule}: SELECT with a
 * top-level {@link SqlJoin}, optionally wrapped in {@link SqlOrderBy} or
 * {@link SqlWith}. Nested joins inside parens or subqueries are NOT walked
 * in Phase 1 — same limitation as JoinSplitRule.
 */
public final class JoinInfoExtractor {

    private static final SqlParserPos POS = SqlParserPos.ZERO;

    private JoinInfoExtractor() {}

    /**
     * Extract every leaf JOIN from a SQL fragment. Returns an empty list if
     * the fragment is not a SELECT (or wrapped SELECT) or has no JOIN.
     *
     * <p>For a left-deep chain {@code A JOIN B JOIN C} this returns the two
     * downstream JOINs (B, C) — i.e. the leaves added to the first table.
     * The semantics match {@code JoinSplitRule.collectLeaves} so the diff
     * operation is consistent with what the rule actually removes.
     */
    public static List<JoinInfo> extractJoins(SqlNode root, DialectProfile profile) {
        if (root == null) return List.of();

        SqlNode inner = root;
        if (inner instanceof SqlOrderBy ob && ob.query != null) {
            inner = ob.query;
        }
        if (inner instanceof SqlWith with && with.body != null) {
            inner = with.body;
        }
        if (!(inner instanceof SqlSelect select)) {
            return List.of();
        }

        SqlNode from = select.getFrom();
        if (!(from instanceof SqlJoin topJoin)) {
            return List.of();
        }

        // Walk the same way JoinSplitRule.collectLeaves does: every leaf is
        // a table added to the previous side via a JOIN. We record the JOIN
        // type that brought that leaf in.
        List<LeafVisit> leaves = new ArrayList<>();
        collectLeaves(topJoin, leaves, profile);

        // position = DFS leaf index, tableName/alias extracted per leaf,
        // onConditionSql = the SqlJoin condition that introduced the leaf.
        List<JoinInfo> result = new ArrayList<>(leaves.size());
        for (int i = 0; i < leaves.size(); i++) {
            LeafVisit leaf = leaves.get(i);
            result.add(new JoinInfo(
                leaf.tableName,
                leaf.tableAlias,
                leaf.joinType,
                leaf.onConditionSql,
                i
            ));
        }
        return result;
    }

    /**
     * Compute the set difference: JOINs present in {@code parent} but absent
     * in {@code child}. Equality is determined by {@link JoinInfo#identityKey()},
     * which ignores {@code position} — so a shared JOIN whose position shifted
     * after a middle leaf was removed is NOT reported as added. Self-joins are
     * distinguished by {@code tableAlias}.
     */
    public static List<JoinInfo> diffJoins(List<JoinInfo> parentJoins, List<JoinInfo> childJoins) {
        if (parentJoins == null || parentJoins.isEmpty()) return List.of();
        if (childJoins == null) childJoins = List.of();

        Set<String> childKeys = childJoins.stream()
            .map(JoinInfo::identityKey)
            .collect(Collectors.toCollection(java.util.LinkedHashSet::new));

        List<JoinInfo> diff = new ArrayList<>();
        for (JoinInfo p : parentJoins) {
            if (!childKeys.contains(p.identityKey())) {
                diff.add(p);
            }
        }
        return diff;
    }

    // ── AST walking (mirrors JoinSplitRule.collectLeaves semantics) ───────────

    private static void collectLeaves(SqlJoin join, List<LeafVisit> out, DialectProfile profile) {
        if (join.getLeft() instanceof SqlJoin leftJoin) {
            collectLeaves(leftJoin, out, profile);
        } else {
            out.add(toVisit(join.getLeft(), join.getJoinType(), join.getCondition(), profile));
        }
        if (join.getRight() instanceof SqlJoin rightJoin) {
            collectLeaves(rightJoin, out, profile);
        } else {
            out.add(toVisit(join.getRight(), join.getJoinType(), join.getCondition(), profile));
        }
    }

    private static LeafVisit toVisit(SqlNode relation, JoinType joinType, SqlNode condition, DialectProfile profile) {
        return new LeafVisit(
            relationName(relation),
            extractAlias(relation),
            joinType,
            conditionToString(condition, profile)
        );
    }

    private static String relationName(SqlNode relation) {
        if (relation instanceof SqlIdentifier id) {
            return id.toString();
        }
        if (relation instanceof SqlSelect) {
            return "subquery";
        }
        if (relation instanceof SqlBasicCall call
            && call.getOperator() == SqlStdOperatorTable.AS
            && call.operandCount() >= 1) {
            String inner = relationName(call.operand(0));
            if (call.operandCount() >= 2 && call.operand(1) instanceof SqlIdentifier alias) {
                return inner + " AS " + alias.toString();
            }
            return inner;
        }
        return relation.getClass().getSimpleName().replace("Sql", "").toLowerCase();
    }

    private static String extractAlias(SqlNode relation) {
        if (relation instanceof SqlIdentifier id) {
            return id.toString();
        }
        if (relation instanceof SqlBasicCall call
            && call.getOperator() == SqlStdOperatorTable.AS
            && call.operandCount() >= 2
            && call.operand(1) instanceof SqlIdentifier alias) {
            return alias.toString();
        }
        return relationName(relation);
    }

    private static String conditionToString(SqlNode condition, DialectProfile profile) {
        if (condition == null) return "";
        try {
            return NodeToSql.toSql(condition, profile);
        } catch (Exception e) {
            throw new IllegalStateException("SQL serialization failed", e);
        }
    }

    /**
     * Mutable holder for one leaf's metadata during traversal.
     * {@code joinType} is the join that introduced this leaf, not the leaf's
     * own type — same convention as {@code JoinSplitRule.LeafRelation}.
     */
    private record LeafVisit(
        String tableName,
        String tableAlias,
        JoinType joinType,
        String onConditionSql
    ) {}

    // Suppress unused warning for the inner helper map (kept for future
    // extensions that may need alias → name lookups).
    @SuppressWarnings("unused")
    private static Map<String, String> _aliasIndex() {
        return new LinkedHashMap<>();
    }
}
