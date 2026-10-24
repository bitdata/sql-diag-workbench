package com.bitdata.sqlworkbench.sql.model;

import java.util.Objects;

/**
 * Identifies a single WHERE condition in a SQL fragment. Used by the
 * Add-WHERE composition feature to compute the set difference between a
 * parent fragment (with more WHERE conditions) and a child fragment
 * produced by PredicateDecompositionRule (which isolates a single
 * condition) or ClauseStrippingRule (which removes WHERE entirely).
 *
 * <p>{@link #position} is the index of the AND-connected condition in
 * the WHERE clause — used only for stable output ordering, not for diff
 * equality.
 */
public record WhereInfo(
    String conditionSql,
    int position
) {
    public WhereInfo {
        Objects.requireNonNull(conditionSql, "conditionSql");
    }

    /**
     * Stable identity key. Two WhereInfo instances with the same key refer
     * to the same WHERE condition. {@code position} is NOT part of the key:
     * isolating a predicate shifts the surviving conditions' positions, and
     * diff must not report them as added. position is used only for stable
     * output ordering.
     */
    public String identityKey() {
        return conditionSql;
    }
}
