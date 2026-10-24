package com.bitdata.sqlworkbench.sql.model;

/**
 * Request body for POST /api/sql/join-diff.
 *
 * <p>Given a parent SQL fragment (with more JOINs) and a child fragment
 * (the result of a JoinSplit rule that removed one or more tables), return
 * the set of JOINs that exist in the parent but not in the child — i.e.
 * the JOINs that can be added back.
 *
 * <p>This endpoint does not return a new SQL fragment or a node id. The
 * caller already knows the parent fragment (it is the current fragment's
 * parent in the DAG); the response is metadata used to drive the
 * "Add JOIN x" UI menu.
 */
public record JoinDiffRequest(
    String parentSql,
    String childSql,
    String dialect
) {}
