package com.bitdata.sqlworkbench.diagnostics.trace;

import com.bitdata.sqlworkbench.sql.DialectProfile;
import com.bitdata.sqlworkbench.sql.SqlParseEngine;
import com.bitdata.sqlworkbench.sql.decompose.JoinInfoExtractor;
import com.bitdata.sqlworkbench.sql.decompose.WhereInfoExtractor;
import com.bitdata.sqlworkbench.sql.model.FragmentNode;
import com.bitdata.sqlworkbench.sql.model.JoinInfo;
import com.bitdata.sqlworkbench.sql.model.WhereInfo;
import org.apache.calcite.sql.SqlNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * Turns a detector {@link EliminationOutcome.Edge} into an {@link EliminationEdge}
 * by parsing the child/parent {@code sqlFragment} with the dialect and diffing
 * their WHERE conditions and JOINs. Failed explanation preserves the observed
 * edge and explicitly marks the diff as degraded.
 */
public final class EliminationEdgeFactory {

    private static final Logger log = LoggerFactory.getLogger(EliminationEdgeFactory.class);

    private final SqlParseEngine sqlParseEngine;

    public EliminationEdgeFactory(SqlParseEngine sqlParseEngine) {
        this.sqlParseEngine = sqlParseEngine;
    }

    public EliminationEdge build(
        EliminationOutcome.Edge edge,
        Map<String, FragmentNode> allNodes,
        DialectProfile profile,
        String dialect
    ) {
        FragmentNode childNode = allNodes.get(edge.childStep().fragmentId());
        FragmentNode parentNode = allNodes.get(edge.parentStep().fragmentId());
        List<WhereInfo> addedWhere = List.of();
        List<JoinInfo> addedJoins = List.of();
        String failure = null;
        if (childNode != null && parentNode != null
            && childNode.sqlFragment() != null && !childNode.sqlFragment().isBlank()
            && parentNode.sqlFragment() != null && !parentNode.sqlFragment().isBlank()) {
            try {
                SqlNode childAst = sqlParseEngine.parse(childNode.sqlFragment(), dialect);
                SqlNode parentAst = sqlParseEngine.parse(parentNode.sqlFragment(), dialect);
                addedWhere = WhereInfoExtractor.diffWhereConditions(
                    WhereInfoExtractor.extractWhereConditions(parentAst, profile),
                    WhereInfoExtractor.extractWhereConditions(childAst, profile));
                addedJoins = JoinInfoExtractor.diffJoins(
                    JoinInfoExtractor.extractJoins(parentAst, profile),
                    JoinInfoExtractor.extractJoins(childAst, profile));
            } catch (Exception e) {
                failure = "SQL differences could not be analyzed: " + e.getClass().getSimpleName();
                // Preserve observations and expose explanation failure to consumers.
                log.warn("EliminationEdge diff parse failed (child={}, parent={}): {}",
                    edge.childStep().fragmentId(), edge.parentStep().fragmentId(), e.getMessage());
            }
        } else { failure = "SQL is unavailable for one or both observed fragments."; }
        EliminationEdge.Kind kind = !addedWhere.isEmpty() ? EliminationEdge.Kind.FILTER
            : !addedJoins.isEmpty() ? EliminationEdge.Kind.JOIN
            : EliminationEdge.Kind.NONE;
        return new EliminationEdge(edge.childStep(), edge.parentStep(),
            addedWhere, addedJoins, kind, failure != null, failure);
    }
}
