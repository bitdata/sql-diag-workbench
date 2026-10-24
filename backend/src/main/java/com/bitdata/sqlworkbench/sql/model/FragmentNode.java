package com.bitdata.sqlworkbench.sql.model;

import java.util.List;

/**
 * A node in the SQL fragment tree.
 *
 * @param id unique node identifier
 * @param type the kind of fragment (ORIGINAL, JOIN_SPLIT, STRIPPED, etc.)
 * @param label human-readable label for UI display
 * @param sqlFragment the SQL text of this fragment
 * @param executable whether the SQL can be executed directly
 * @param executionHint guidance on how to interpret execution results
 * @param nodeCount number of child nodes (populated during tree building)
 * @param ruleName which rule produced this node (null for original)
 * @param children child fragment nodes
 * @param parentIds ancestor node IDs (first = direct parent)
 * @param transform the transform that produced this node
 * @param source whether this node was generated automatically or by user editing
 * @param canonicalSql ADR-0001 identity source — normalized SQL text
 * @param editParentId edit-chain single link (ADR-0002/0003), null for non-edit nodes
 * @param createdAt ISO 8601 UTC timestamp of node creation
 * @param executionSummary per-node execution metrics, null until first successful run
 * @param canonicalizationError non-null when this node's identity was derived from
 *     raw SQL instead of a clean canonicalization — either parsing failed or the
 *     canonical token stream contained unsupported node types (ADR-0001). A non-null
 *     value means "the id came from the raw text, not from a normalized form", so the
 *     identity must not be treated as an equivalence identity. {@code null} = clean.
 */
public record FragmentNode(
    String id,
    FragmentType type,
    String label,
    String sqlFragment,
    boolean executable,
    String executionHint,
    int nodeCount,
    String ruleName,
    List<FragmentNode> children,

    /* lineage fields — for tracking exploration path and user edits */
    List<String> parentIds,
    TransformRecord transform,
    SourceKind source,

    /* spec §4.2 — lineage panel data (default null; populated on demand) */
    String canonicalSql,
    String editParentId,
    String createdAt,
    ExecutionSummary executionSummary,

    /* ADR-0001 — identity degradation marker (null = clean identity) */
    String canonicalizationError
) {
    /**
     * Compact constructor that enforces null-safe defaults.
     * Existing callers with the 9-parameter constructor continue to work unchanged.
     */
    public FragmentNode(
        String id, FragmentType type, String label, String sqlFragment,
        boolean executable, String executionHint, int nodeCount,
        String ruleName, List<FragmentNode> children
    ) {
        this(id, type, label, sqlFragment, executable, executionHint, nodeCount,
             ruleName, children, List.of(), null, SourceKind.AUTO,
             null, null, null, null, null);
    }

    /**
     * 16-argument compatibility constructor — mirrors the record's component list
     * before {@code canonicalizationError} was appended, leaving the degradation
     * marker {@code null} (clean). Call sites that predate the marker keep compiling
     * unchanged; only {@link com.bitdata.sqlworkbench.sql.decompose.DecompositionRuleEngine}
     * populates it (via the full 17-argument constructor).
     */
    public FragmentNode(
        String id, FragmentType type, String label, String sqlFragment,
        boolean executable, String executionHint, int nodeCount,
        String ruleName, List<FragmentNode> children,
        List<String> parentIds, TransformRecord transform, SourceKind source,
        String canonicalSql, String editParentId, String createdAt,
        ExecutionSummary executionSummary
    ) {
        this(id, type, label, sqlFragment, executable, executionHint, nodeCount,
             ruleName, children, parentIds, transform, source,
             canonicalSql, editParentId, createdAt, executionSummary, null);
    }

    /**
     * Factory for creating a node with lineage information.
     */
    public static FragmentNode withLineage(
        String id, FragmentType type, String label, String sqlFragment,
        boolean executable, String executionHint, int nodeCount,
        String ruleName, List<FragmentNode> children,
        List<String> parentIds, TransformRecord transform, SourceKind source
    ) {
        return new FragmentNode(id, type, label, sqlFragment, executable,
            executionHint, nodeCount, ruleName, children, parentIds, transform, source,
            null, null, null, null);
    }

    /**
     * Factory for creating a user-edited fragment node (ADR-0001/0002/0003).
     * All lineage fields are populated: canonicalSql, editParentId, createdAt.
     * parentIds is empty — user edits use editParentId, not decomposition edges.
     */
    public static FragmentNode userEdit(
        String id, String label, String sql, String canonicalSql,
        String editParentId, String createdAt
    ) {
        return new FragmentNode(
            id,
            FragmentType.USER_EDITED,
            label,
            sql,
            true,                // executable
            null,                // executionHint
            0,                   // nodeCount
            null,                // ruleName
            List.of(),           // children
            List.of(),           // parentIds — empty (not from decomposition)
            null,                // transform
            SourceKind.USER,     // source
            canonicalSql,
            editParentId,
            createdAt,
            null                 // executionSummary
        );
    }
}
