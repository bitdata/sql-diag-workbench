package com.bitdata.sqlworkbench.sql.model;

import org.apache.calcite.sql.JoinType;

import java.util.Objects;

/**
 * Identifies a single JOIN in a SQL fragment. Used by Add-Join composition
 * to compute the set difference between a parent fragment and a child fragment
 * produced by JoinSplitRule.
 *
 * <p>{@link #position} is the leaf index of the relation in left-deep DFS
 * order — used only for stable output ordering.
 */
public record JoinInfo(
    String tableName,
    String tableAlias,
    JoinType joinType,
    String onConditionSql,
    int position
) {
    public JoinInfo {
        Objects.requireNonNull(tableName, "tableName");
        Objects.requireNonNull(joinType, "joinType");
        // onConditionSql and tableAlias may be null/empty for CROSS JOINs
    }

    /**
     * Stable identity key: any two JoinInfo instances with the same key refer
     * to the same JOIN in the SQL fragment. {@code position} is NOT part of
     * the key (removing a middle leaf shifts survivors' positions). Self-joins
     * are disambiguated by {@code tableAlias}; same table+alias+type+condition
     * is a true duplicate and dedup is acceptable.
     */
    public String identityKey() {
        return tableName + "|" + (tableAlias == null ? "" : tableAlias)
            + "|" + joinType.name() + "|" + (onConditionSql == null ? "" : onConditionSql);
    }
}
