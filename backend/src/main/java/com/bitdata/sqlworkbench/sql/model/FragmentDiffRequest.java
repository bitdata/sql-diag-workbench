package com.bitdata.sqlworkbench.sql.model;

import java.util.List;

/**
 * Request body for POST /api/sql/fragment-diff.
 *
 * <p>Generic version of the original join-diff endpoint. Takes a
 * {@code type} discriminator and returns the corresponding
 * composition data:
 *
 * <ul>
 *   <li>{@code "join"} — JOINs present in parent but absent in child
 *       (returns {@code availableJoins})</li>
 *   <li>{@code "where"} — WHERE conditions present in parent but
 *       absent in child (returns {@code availableWhereConditions})</li>
 *   <li>{@code "aggregate"} — GROUP BY / HAVING constructs present in
 *       parent but absent in child (returns {@code availableAggregates})</li>
 * </ul>
 *
 * <p>The split keeps the original /api/sql/join-diff endpoint working
 * for Phase 1 callers; this new endpoint is the unified Phase 2 entry
 * point. Both go through the same extractor pattern internally.
 */
public record FragmentDiffRequest(
    String type,           // "join" | "where" | "aggregate"
    String parentSql,
    String childSql,
    String dialect
) {
    public static final String TYPE_JOIN = "join";
    public static final String TYPE_WHERE = "where";
    public static final String TYPE_AGGREGATE = "aggregate";

    public record JoinEntry(
        String tableName,
        String tableAlias,
        String joinType,
        String onConditionSql,
        int position
    ) {}

    public record WhereEntry(
        String conditionSql,
        int position
    ) {}

    public record AggregateEntry(
        String kind,           // "GROUP BY" | "HAVING"
        String expressionSql,
        int position
    ) {}

    /**
     * Response body — every field is optional since the server only
     * populates the one corresponding to the requested type.
     */
    public record FragmentDiffResponse(
        List<JoinEntry> availableJoins,
        List<WhereEntry> availableWhereConditions,
        List<AggregateEntry> availableAggregates, boolean degraded, String errorReason
    ) {
        public FragmentDiffResponse(List<JoinEntry> joins,List<WhereEntry> where,List<AggregateEntry> aggregates) { this(joins,where,aggregates,false,null); }
    }
}
