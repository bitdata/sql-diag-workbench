package com.bitdata.sqlworkbench.session;

import com.bitdata.sqlworkbench.sql.diff.StructuralSqlCodec;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;

/** Explicit current-policy context for pre-existing public endpoint behavior tests. */
public final class GraphHttpFixtures {
    private GraphHttpFixtures() {}
    public static String parentId() { return StructuralSqlCodec.canonicalize("SELECT 1","postgresql").id(); }
    public static String admit(String raw, SessionStore store) throws Exception {
        var json=new ObjectMapper();
        var body=(ObjectNode)json.readTree(raw);
        String sessionId=body.path("sessionId").asText(UUID.randomUUID().toString());
        if (store.findById(sessionId).isEmpty()) store.save(Session.builder().id(sessionId)
            .identityPolicy(StructuralSqlCodec.POLICY).build());
        var session=store.findById(sessionId).orElseThrow();
        body.set("identityContext",json.valueToTree(new IdentityContext(session.id(),session.identityPolicy(),session.workspaceRevision())));
        var proofs=new ArrayList<NodeProof>();
        if (body.has("parentFragmentId")) {
            var parent=StructuralSqlCodec.canonicalize("SELECT 1","postgresql");
            body.put("parentFragmentId",parent.id());
            proofs.add(new NodeProof(parent.id(),"SELECT 1",parent.canonical()));
            if (!body.path("dagContextIds").isEmpty()) {
                String existing="SELECT * FROM t WHERE a > 0";
                var form=StructuralSqlCodec.canonicalize(existing,"postgresql");
                for(var id:body.path("dagContextIds")) {
                    if (!form.id().equals(id.asText())) throw new AssertionError("Test needs an actual node proof");
                }
                proofs.add(new NodeProof(form.id(),existing,form.canonical()));
            }
        }
        body.set("nodes",json.valueToTree(proofs));
        return json.writeValueAsString(body);
    }
    public static String snapshot(String sql,String dialect) throws Exception {
        var json=new ObjectMapper(); var form=StructuralSqlCodec.canonicalize(sql,dialect);
        if(form.degraded()) throw new AssertionError(form.errorReason());
        var node=json.createObjectNode().put("id",form.id()).put("sqlFragment",sql).put("canonicalSql",form.canonical())
            .put("label","Original").put("type","ORIGINAL").put("executable",true).put("nodeCount",0);
        node.set("children",json.createArrayNode()); node.set("parentIds",json.createArrayNode());
        var graph=json.createObjectNode().put("rootNodeId",form.id()).put("completeness","recorded");
        graph.set("nodes",json.createArrayNode().add(node)); graph.set("edges",json.createArrayNode());graph.set("ruleResults",json.createArrayNode());
        var snapshot=json.createObjectNode().put("version",3).put("identityPolicy",StructuralSqlCodec.POLICY)
            .put("parsedSql",sql).put("dialect",dialect).put("selectedNodeId",form.id()).put("viewerMode","browse").put("workspaceMode","data");
        snapshot.set("graph",graph);snapshot.set("treeProjection",node);
        return json.writeValueAsString(snapshot);
    }
}
