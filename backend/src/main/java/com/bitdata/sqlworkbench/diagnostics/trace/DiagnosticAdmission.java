package com.bitdata.sqlworkbench.diagnostics.trace;

import com.bitdata.sqlworkbench.connection.*;
import com.bitdata.sqlworkbench.session.*;
import com.bitdata.sqlworkbench.sql.model.FragmentNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import java.util.*;

/** Server admission shared by graph observations and independently certified sources. */
@Component
public final class DiagnosticAdmission {
    private final WorkspaceIdentity identity;
    private final ConnectionService connections;
    private final ObjectMapper json;
    public DiagnosticAdmission(WorkspaceIdentity identity,ConnectionService connections,ObjectMapper json) {
        this.identity=identity;this.connections=connections;this.json=json;
    }
    public static WorkspaceIdentity.Failure failure(int status,String code) { return new WorkspaceIdentity.Failure(status,code); }
    public Session require(DiagnosticContext context,String connectionId) {
        var config=connections.getById(connectionId).orElseThrow(()->failure(404,"CONNECTION_NOT_FOUND"));
        Session session=identity.require(context==null?null:context.identityContext(),config.dialect());
        if(context.workspaceInstanceId()==null||!context.workspaceInstanceId().matches("[0-9a-f]{32}"))
            throw failure(400,"WORKSPACE_INSTANCE_REQUIRED");
        if(!context.workspaceInstanceId().equals(session.workspaceInstanceId())) throw failure(409,"WORKSPACE_INSTANCE_MISMATCH");
        if(!Objects.equals(session.connectionId(),connectionId))throw failure(409,"WORKSPACE_CONNECTION_MISMATCH");
        return session;
    }
    public String connectionVersion(String connectionId) {
        var c=connections.getById(connectionId).orElseThrow(()->failure(404,"CONNECTION_NOT_FOUND"));
        try {
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(
                json.writeValueAsBytes(Arrays.asList(ConnectionIdentity.revision(c),c.readonly(),c.keychainRef(),c.credentialState()))));
        } catch(java.security.NoSuchAlgorithmException|com.fasterxml.jackson.core.JsonProcessingException e) {
            throw failure(503,"CONNECTION_IDENTITY_UNAVAILABLE");
        }
    }
    public void revalidate(DiagnosticContext context,String connectionId,String version) {
        require(context,connectionId);
        if(!Objects.equals(version,connectionVersion(connectionId)))throw failure(409,"CONNECTION_CHANGED");
    }
    public TraceInputValidator.Scope graph(TraceRequest request) {
        Session session=require(request.diagnosticContext(),request.connectionId());
        try {
            var submitted=new TraceInputValidator().validate(json.readValue(request.fragmentTreeJson(),FragmentNode.class));
            var stored=stored(session);
            if(!submitted.children().equals(stored.children())||!submitted.leafFirst().equals(stored.leafFirst()))throw failure(409,"SAVED_GRAPH_MISMATCH");
            for(var node:submitted.nodes().values()) {
                member(session,stored,new NodeProof(node.id(),node.sqlFragment(),node.canonicalSql()));
                var saved=stored.nodes().get(node.id());
                if(node.executable()!=saved.executable()||!Objects.equals(node.canonicalizationError(),saved.canonicalizationError()))
                    throw failure(409,"SAVED_NODE_MISMATCH");
            }
            if(!submitted.occurrences().stream().map(o->Arrays.asList(o.parentId(),o.node().id(),o.ordinal())).toList()
                .equals(stored.occurrences().stream().map(o->Arrays.asList(o.parentId(),o.node().id(),o.ordinal())).toList()))
                throw failure(409,"SAVED_GRAPH_MISMATCH");
            return submitted;
        } catch(com.fasterxml.jackson.core.JsonProcessingException e) { throw failure(400,"TRACE_INPUT_INVALID"); }
    }
    public void source(DiagnosticContext context,String connectionId,String sql,NodeProof proof) {
        Session session=require(context,connectionId);
        if(proof==null||!Objects.equals(sql,proof.sqlFragment()))throw failure(409,"SOURCE_SQL_IDENTITY_MISMATCH");
        member(session,stored(session),proof);
    }
    private TraceInputValidator.Scope stored(Session session) {
        if(session.workspaceSnapshotJson()==null)throw failure(409,"SAVED_GRAPH_REQUIRED");
        try {
            var snapshot=json.readTree(session.workspaceSnapshotJson());
            new WorkspaceSnapshotIdentity(identity).validate(snapshot,session.dialect());
            var graph=snapshot.path("graph");
            if(graph.isNull())throw failure(409,"SAVED_GRAPH_REQUIRED");
            Map<String,com.fasterxml.jackson.databind.JsonNode> nodes=new LinkedHashMap<>();
            for(var n:graph.path("nodes"))nodes.put(n.path("id").asText(),n);
            Map<String,List<String>> children=new LinkedHashMap<>();
            for(var r:graph.path("ruleResults")) {
                var ids=children.computeIfAbsent(r.path("sourceNodeId").asText(),ignored->new ArrayList<>());
                for(var child:r.path("childIds"))if(!ids.contains(child.asText()))ids.add(child.asText());
            }
            return new TraceInputValidator().validate(rebuild(graph.path("rootNodeId").asText(),nodes,children,new HashSet<>(),new int[]{0}));
        }
        catch(com.fasterxml.jackson.core.JsonProcessingException|TraceInputValidator.Invalid e){throw failure(409,"SAVED_GRAPH_INVALID");}
    }
    private FragmentNode rebuild(String id,Map<String,com.fasterxml.jackson.databind.JsonNode> nodes,Map<String,List<String>> edges,
                                 Set<String> ancestors,int[] occurrences)throws com.fasterxml.jackson.core.JsonProcessingException {
        if(!nodes.containsKey(id)||ancestors.contains(id)||++occurrences[0]>512||ancestors.size()>64)throw failure(409,"SAVED_GRAPH_INVALID");
        var object=(com.fasterxml.jackson.databind.node.ObjectNode)nodes.get(id).deepCopy();
        var children=object.putArray("children");object.putArray("parentIds");
        var next=new HashSet<>(ancestors);next.add(id);
        for(var child:edges.getOrDefault(id,List.of()))children.add(json.valueToTree(rebuild(child,nodes,edges,next,occurrences)));
        return json.treeToValue(object,FragmentNode.class);
    }
    private void member(Session session,TraceInputValidator.Scope stored,NodeProof proof) {
        identity.verify(proof,session.dialect());var node=stored.nodes().get(proof.id());
        if(node==null&&session.workspaceSnapshotJson()!=null) {
            try {for(var candidate:json.readTree(session.workspaceSnapshotJson()).path("graph").path("nodes"))
                if(proof.id().equals(candidate.path("id").asText()))node=json.treeToValue(candidate,FragmentNode.class);
            }catch(com.fasterxml.jackson.core.JsonProcessingException e){throw failure(409,"SAVED_GRAPH_INVALID");}
        }
        if(node==null||!Objects.equals(proof.sqlFragment(),node.sqlFragment())||!Objects.equals(proof.canonicalSql(),node.canonicalSql()))
            throw failure(409,"SAVED_NODE_MISMATCH");
    }
}
