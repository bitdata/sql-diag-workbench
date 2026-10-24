package com.bitdata.sqlworkbench.sql.model;

public enum FragmentType {
    /** Original unmodified SQL (root node) */
    ORIGINAL,

    /** Clause removed (ORDER BY, WHERE, HAVING, DISTINCT, LIMIT) */
    STRIPPED,

    /** Branch extracted from UNION / INTERSECT / EXCEPT */
    SET_SPLIT,

    /** Predicate decomposed (AND-condition removed) */
    PREDICATE_DECOMPOSED,

    /** Column pruned from SELECT list */
    COLUMN_PRUNED,

    /** JOIN split into single-table or fewer-table query */
    JOIN_SPLIT,

    /** Subquery extracted as standalone query */
    SUBQUERY_EXTRACTED,

    /** CTE inlined or expanded */
    CTE_EXPANDED,

    /** User manually edited or created this fragment */
    USER_EDITED
}
