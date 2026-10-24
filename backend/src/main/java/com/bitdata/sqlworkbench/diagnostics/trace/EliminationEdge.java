package com.bitdata.sqlworkbench.diagnostics.trace;

import com.bitdata.sqlworkbench.sql.model.JoinInfo;
import com.bitdata.sqlworkbench.sql.model.WhereInfo;

import java.util.List;

/**
 * Enriched elimination edge: the detector's raw {@code Edge} plus the budget
 * diff between parent and child fragments. Built by {@link EliminationEdgeFactory}
 * and carried on {@link TraceResult}; diagnostic rules attribute elimination by
 * reading {@link #addedWhere()} / {@link #addedJoins()} instead of the parent
 * node's type or label.
 *
 * <p>The {@link Kind} field is <strong>informational-only</strong>: it is derived
 * from which diff list is non-empty, but production rules do not read it. They
 * read {@link #addedWhere()} / {@link #addedJoins()} directly (both may be
 * non-empty simultaneously). {@code Kind} is kept for test assertions and
 * diagnostic readability.
 */
public record EliminationEdge(
    TraceStep childStep,
    TraceStep parentStep,
    List<WhereInfo> addedWhere,
    List<JoinInfo> addedJoins,
    Kind kind,
    boolean degraded,
    String errorReason
) {
    public EliminationEdge(TraceStep child, TraceStep parent, List<WhereInfo> where, List<JoinInfo> joins, Kind kind) {
        this(child, parent, where, joins, kind, false, null);
    }
    /**
     * Informational-only classification of the diff that produced this edge.
     * Derived from which of {@link #addedWhere()} / {@link #addedJoins()} is
     * non-empty. Production rules do <em>not</em> read this — they read the
     * diff lists directly (both may be non-empty simultaneously, e.g. a step
     * that added both a WHERE and a JOIN). Kept for test assertions and human
     * readability.
     */
    public enum Kind { FILTER, JOIN, NONE }
}
