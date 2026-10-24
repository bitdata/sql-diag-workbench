package com.bitdata.sqlworkbench.sql.model;

/**
 * Record of the transformation that produced a fragment node.
 */
public record TransformRecord(
    /** What kind of transform produced this node */
    TransformKind kind,
    /** Which rule performed the transform (null for USER_EDIT) */
    String ruleName,
    /** Human-readable description: "Removed orders JOIN" */
    String description,
    /** Brief diff summary: "-2 columns, -1 condition, -ORDER BY" */
    String diffSummary
) {
    public enum TransformKind {
        DECOMPOSE,   // produced by a decomposition rule
        REWRITE,     // produced by a rewrite (e.g., format SQL)
        USER_EDIT,   // produced by user editing
        PARSE        // produced by initial parsing
    }
}