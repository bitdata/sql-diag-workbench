package com.bitdata.sqlworkbench.sql.model;

import java.util.Objects;

/**
 * Identifies a single aggregate construct (GROUP BY columns or HAVING
 * clause) in a SQL fragment. Used by the Add-Aggregate composition
 * feature to compute the set difference between a parent fragment and
 * a child fragment produced by ClauseStrippingRule (which removes
 * GROUP BY / HAVING).
 *
 * <p>Phase 2 simplification: we don't try to subtract individual
 * columns from a multi-column GROUP BY — we treat the whole GROUP BY
 * and the whole HAVING as atomic units (one row each, like the
 * original ClauseStrippingRule output).
 */
public record AggregateInfo(
    String kind,           // "GROUP BY" | "HAVING"
    String expressionSql,  // the columns list, or the HAVING predicate
    int position
) {
    public AggregateInfo {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(expressionSql, "expressionSql");
    }

    public String identityKey() {
        return kind + "|" + expressionSql + "|" + position;
    }
}
