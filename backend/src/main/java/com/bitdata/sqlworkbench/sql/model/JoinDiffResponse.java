package com.bitdata.sqlworkbench.sql.model;

import java.util.List;

/**
 * Response body for POST /api/sql/join-diff.
 *
 * @param availableJoins the JOINs present in the parent fragment but absent
 *                       from the child. Empty if no JoinSplit has been
 *                       applied (or the fragments are identical).
 */
public record JoinDiffResponse(
    List<JoinDiffEntry> availableJoins, boolean degraded, String errorReason
) {
    public JoinDiffResponse(List<JoinDiffEntry> joins) { this(joins,false,null); }
    /**
     * One JOIN that can be added back. {@code joinType} is the string form
     * of {@link org.apache.calcite.sql.JoinType} so the JSON payload is
     * dialect-agnostic and easy to consume on the frontend.
     */
    public record JoinDiffEntry(
        String tableName,
        String tableAlias,
        String joinType,        // "INNER" | "LEFT" | "RIGHT" | "FULL" | "CROSS" | "COMMA"
        String onConditionSql,
        int position
    ) {}
}
