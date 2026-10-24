package com.bitdata.sqlworkbench.sql.decompose;

/**
 * Three semantic levels at which decomposition rules operate.
 *
 * STRUCTURE  - data source participation (which tables/CTEs/subqueries contribute)
 * CLAUSE     - optional clause composition (ORDER BY, LIMIT, WHERE, HAVING)
 * PREDICATE  - intra-clause logic (conditions within a clause)
 */
public enum DecompositionLevel {
    /** Data source participation — which tables/CTEs/subqueries contribute to the query */
    STRUCTURE,
    /** Optional clause composition — which optional clauses are present */
    CLAUSE,
    /** Intra-clause logic — how conditions within a clause combine */
    PREDICATE
}