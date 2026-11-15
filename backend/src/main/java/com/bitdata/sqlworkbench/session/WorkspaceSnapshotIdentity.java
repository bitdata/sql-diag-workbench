package com.bitdata.sqlworkbench.session;

import com.bitdata.sqlworkbench.sql.diff.StructuralSqlCodec;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Identity checks supplement, rather than replace, existing graph/effect/cycle validation. */
@Component
public class WorkspaceSnapshotIdentity {
    private final WorkspaceIdentity identity;
    public WorkspaceSnapshotIdentity(WorkspaceIdentity identity) { this.identity = identity; }

    public void validate(JsonNode snapshot, String dialect) {
        if (!snapshot.path("version").isIntegralNumber() || snapshot.path("version").asLong() != 3
                || !StructuralSqlCodec.POLICY.equals(snapshot.path("identityPolicy").asText()))
            throw new WorkspaceIdentity.Failure(400,"SNAPSHOT_IDENTITY_POLICY_MISMATCH");
        if (!Objects.equals(dialect,snapshot.path("dialect").asText()))
            throw new WorkspaceIdentity.Failure(400,"SNAPSHOT_DIALECT_MISMATCH");
        if (snapshot.path("graph").isNull()) return; // Text-only drafts may have unsupported SQL.
        Map<String,JsonNode> nodes = new HashMap<>();
        for (var node : snapshot.path("graph").path("nodes")) {
            identity.verify(new NodeProof(node.path("id").asText(),node.path("sqlFragment").asText(),
                node.hasNonNull("canonicalSql") ? node.path("canonicalSql").asText() : null),dialect);
            if (nodes.put(node.path("id").asText(),node) != null)
                throw new WorkspaceIdentity.Failure(400,"DUPLICATE_GRAPH_NODE");
            if (node.hasNonNull("canonicalizationError"))
                throw new WorkspaceIdentity.Failure(422,"CANONICAL_UNAVAILABLE");
        }
        for (var node : nodes.values()) if (node.hasNonNull("editParentId")
                && !nodes.containsKey(node.path("editParentId").asText()))
            throw new WorkspaceIdentity.Failure(400,"EDIT_PARENT_NOT_IN_GRAPH");
        var root = StructuralSqlCodec.canonicalize(snapshot.path("parsedSql").asText(),dialect);
        if (root.degraded() || !Objects.equals(root.id(),snapshot.path("graph").path("rootNodeId").asText()))
            throw new WorkspaceIdentity.Failure(409,"PARSED_ROOT_IDENTITY_MISMATCH");
        if (!snapshot.path("selectedNodeId").isNull() && !nodes.containsKey(snapshot.path("selectedNodeId").asText()))
            throw new WorkspaceIdentity.Failure(400,"SELECTION_NOT_IN_GRAPH");
        projection(snapshot.path("treeProjection"),nodes,0);
    }

    private void projection(JsonNode projection, Map<String,JsonNode> nodes, int depth) {
        if (depth > 256) throw new WorkspaceIdentity.Failure(400,"PROJECTION_DEPTH_LIMIT");
        var source = nodes.get(projection.path("id").asText());
        if (source == null) throw new WorkspaceIdentity.Failure(400,"PROJECTION_NODE_MISSING");
        for (String field : java.util.List.of("sqlFragment","canonicalSql","executable","canonicalizationError")) {
            if (!source.path(field).equals(projection.path(field)))
                throw new WorkspaceIdentity.Failure(409,"PROJECTION_NODE_IDENTITY_MISMATCH");
        }
        for (var child : projection.path("children")) projection(child,nodes,depth+1);
    }
}
