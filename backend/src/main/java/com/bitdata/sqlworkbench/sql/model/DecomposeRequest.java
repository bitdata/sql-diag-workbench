package com.bitdata.sqlworkbench.sql.model;

/**
 * Request to apply a single decomposition rule to a SQL fragment.
 *
 * @param sqlFragment  the source SQL text
 * @param dialect      target SQL dialect (e.g. "postgresql")
 * @param ruleName     which decomposition rule to apply
 * @param sourceNodeId stable id of the source fragment in the DAG (used to populate
 *                     child nodes' parentIds). Optional for back-compat — when null
 *                     or blank, the engine falls back to deriving a synthetic id from
 *                     the source SQL.
 */
public record DecomposeRequest(
    String sqlFragment,
    String dialect,
    String ruleName,
    String sourceNodeId,
    String connectionId
) {
    public DecomposeRequest(String sqlFragment, String dialect, String ruleName, String sourceNodeId) {
        this(sqlFragment, dialect, ruleName, sourceNodeId, null);
    }
    public DecomposeRequest(String sqlFragment, String dialect, String ruleName) {
        this(sqlFragment, dialect, ruleName, null);
    }
}
