package com.bitdata.sqlworkbench.sql.decompose;

import com.bitdata.sqlworkbench.sql.DialectProfile;
import com.bitdata.sqlworkbench.sql.NodeToSql;
import com.bitdata.sqlworkbench.sql.model.AggregateInfo;

import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlNodeList;
import org.apache.calcite.sql.SqlOrderBy;
import org.apache.calcite.sql.SqlSelect;
import org.apache.calcite.sql.SqlWith;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Read-only utilities for inspecting the GROUP BY / HAVING clauses of
 * a SQL fragment. Used by the Add-Aggregate composition feature to
 * compute the set difference between a parent fragment (with aggregates
 * stripped) and a child fragment produced by {@code ClauseStrippingRule}.
 *
 * <p>Phase 2 simplification: we treat each clause (GROUP BY list,
 * HAVING predicate) as a single atomic unit. The diff then answers
 * "did the parent have a GROUP BY that the child doesn't?" — same
 * granularity as the original decomposition rule.
 */
public final class AggregateInfoExtractor {

    private static final String KIND_GROUP_BY = "GROUP BY";
    private static final String KIND_HAVING = "HAVING";

    private AggregateInfoExtractor() {}

    /**
     * Extract GROUP BY columns (as one entry) and HAVING predicate
     * (as one entry). Returns up to 2 entries, in (GROUP BY, HAVING)
     * order. Empty list if neither clause is present.
     */
    public static List<AggregateInfo> extractAggregates(SqlNode root, DialectProfile profile) {
        if (root == null) return List.of();
        SqlNode inner = root;
        if (inner instanceof SqlOrderBy ob && ob.query != null) inner = ob.query;
        if (inner instanceof SqlWith with && with.body != null) inner = with.body;
        if (!(inner instanceof SqlSelect select)) return List.of();

        List<AggregateInfo> result = new ArrayList<>(2);

        SqlNodeList groupList = select.getGroup();
        if (groupList != null && !groupList.isEmpty()) {
            result.add(new AggregateInfo(KIND_GROUP_BY, listToString(groupList, profile), 0));
        }

        SqlNode having = select.getHaving();
        if (having != null) {
            result.add(new AggregateInfo(KIND_HAVING, nodeToString(having, profile), 1));
        }

        return result;
    }

    /**
     * Set difference: aggregates present in {@code parent} but absent
     * in {@code child}.
     */
    public static List<AggregateInfo> diffAggregates(
        List<AggregateInfo> parent,
        List<AggregateInfo> child
    ) {
        if (parent == null || parent.isEmpty()) return List.of();
        if (child == null) child = List.of();
        Set<String> childKeys = new LinkedHashSet<>();
        for (AggregateInfo a : child) childKeys.add(a.identityKey());
        List<AggregateInfo> diff = new ArrayList<>();
        for (AggregateInfo p : parent) {
            if (!childKeys.contains(p.identityKey())) diff.add(p);
        }
        return diff;
    }

    private static String listToString(SqlNodeList list, DialectProfile profile) {
        try {
            return NodeToSql.toSql(list, profile);
        } catch (Exception e) {
            throw new IllegalStateException("SQL serialization failed", e);
        }
    }

    private static String nodeToString(SqlNode node, DialectProfile profile) {
        if (node == null) return "";
        try {
            return NodeToSql.toSql(node, profile);
        } catch (Exception e) {
            throw new IllegalStateException("SQL serialization failed", e);
        }
    }
}
