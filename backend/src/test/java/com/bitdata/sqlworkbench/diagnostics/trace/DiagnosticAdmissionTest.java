package com.bitdata.sqlworkbench.diagnostics.trace;

import com.bitdata.sqlworkbench.config.SqliteConfig;
import com.bitdata.sqlworkbench.connection.*;
import com.bitdata.sqlworkbench.diagnostics.source.*;
import com.bitdata.sqlworkbench.session.*;
import com.bitdata.sqlworkbench.sql.diff.StructuralSqlCodec;
import com.bitdata.sqlworkbench.sql.model.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DiagnosticAdmissionTest {
    @TempDir Path dir;
    final ObjectMapper json=new ObjectMapper();
    JdbcTemplate jdbc;SessionStore store;DiagnosticAdmission admission;ConnectionService connections;
    FragmentNode root,child,edit;DiagnosticContext context;String tree;ObjectNode snapshot;
    ConnectionConfig config() {return new ConnectionConfig("c","test","localhost",1433,"db","user","sqlserver","ref",true,"created","updated","sqlserver",true);}
    FragmentNode node(String sql) {var f=StructuralSqlCodec.canonicalize(sql,"sqlserver");return new FragmentNode(f.id(),FragmentType.ORIGINAL,"n",sql,true,null,0,null,List.of(),List.of(),null,SourceKind.AUTO,f.canonical(),null,null,null,null);}
    @BeforeEach void setup()throws Exception {
        jdbc=new JdbcTemplate(new DriverManagerDataSource("jdbc:sqlite:"+dir.resolve("test.db")));
        ReflectionTestUtils.invokeMethod(new SqliteConfig(),"initSchema",jdbc);store=new SessionStore(jdbc);
        connections=mock(ConnectionService.class);when(connections.getById("c")).thenReturn(Optional.of(config()));
        admission=new DiagnosticAdmission(new WorkspaceIdentity(store),connections,json);
        root=node("SELECT 1 AS id");child=node("SELECT 2 AS id");edit=node("SELECT 3 AS id");
        snapshot=json.createObjectNode();snapshot.put("version",3);snapshot.put("identityPolicy",StructuralSqlCodec.POLICY);
        snapshot.put("dialect","sqlserver");snapshot.put("parsedSql",root.sqlFragment());snapshot.putNull("selectedNodeId");
        snapshot.set("treeProjection",json.valueToTree(root));
        var graph=snapshot.putObject("graph");graph.put("rootNodeId",root.id());graph.set("nodes",json.valueToTree(List.of(root,child,edit)));
        graph.putArray("ruleResults").addObject().put("sourceNodeId",root.id()).putArray("childIds").add(child.id());
        var expanded=(ObjectNode)json.valueToTree(root);expanded.putArray("children").add(json.valueToTree(child));tree=json.writeValueAsString(expanded);
        store.save(Session.builder().id("s").dialect("sqlserver").connectionId("c").identityPolicy(StructuralSqlCodec.POLICY)
            .workspaceSnapshotJson(json.writeValueAsString(snapshot)).fragmentTreeJson(json.writeValueAsString(root)).build());
        context=new DiagnosticContext("s",StructuralSqlCodec.POLICY,0L,store.findById("s").orElseThrow().workspaceInstanceId());
    }
    TraceRequest request(String input) {return new TraceRequest("c",input,List.of(new KeyValue("id","1",KeyValue.Type.DECIMAL)),30,TraceMode.CARDINALITY,null,List.of(),context,null);}
    void denied(String code,Runnable run) {assertEquals(code,assertThrows(WorkspaceIdentity.Failure.class,run::run).code);}
    @Test void completeSavedDagIsAdmittedEvenWhenProjectionIsCollapsed() {
        assertEquals(2,admission.graph(request(tree)).nodes().size());
        denied("SAVED_GRAPH_MISMATCH",()->admission.graph(request(json.valueToTree(root).toString())));
        admission.source(context,"c",edit.sqlFragment(),new NodeProof(edit.id(),edit.sqlFragment(),edit.canonicalSql()));
    }
    @Test void changedExecutableAndForgedSqlAreRejected()throws Exception {
        var changed=(ObjectNode)json.readTree(tree);changed.put("executable",false);
        denied("SAVED_NODE_MISMATCH",()->admission.graph(request(changed.toString())));
        denied("NODE_IDENTITY_MISMATCH",()->admission.source(context,"c","SELECT 99",new NodeProof(root.id(),"SELECT 99",root.canonicalSql())));
    }
    @Test void instanceSurvivesSaveAndMigrationButNotRecreation() {
        ReflectionTestUtils.invokeMethod(new SqliteConfig(),"initSchema",jdbc);
        assertEquals(context.workspaceInstanceId(),store.findById("s").orElseThrow().workspaceInstanceId());
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("UPDATE sessions SET workspace_instance_id='changed' WHERE id='s'"));
        var updated=store.replaceWorkspace(context.identityContext(),root.sqlFragment(),"sqlserver","c",snapshot.toString(),root.toString());
        assertEquals(context.workspaceInstanceId(),updated.workspaceInstanceId());
        denied("WORKSPACE_REVISION_CONFLICT",()->admission.require(context,"c"));
        store.delete("s");store.save(Session.builder().id("s").dialect("sqlserver").connectionId("c").identityPolicy(StructuralSqlCodec.POLICY).build());
        denied("WORKSPACE_INSTANCE_MISMATCH",()->admission.require(context,"c"));
    }
    @Test void receiptsUseServerSqlAndBecomeInvalidAfterConfigOrInstanceChange() {
        var receipts=new SourceObservationReceipts(admission,json);
        var prep=new PhysicalSourcePlanService.Request("c",root.sqlFragment(),request(tree).keys(),context,new NodeProof(root.id(),root.sqlFragment(),root.canonicalSql()));
        String receipt=receipts.issue(prep,"SELECT 7 AS id",List.of(new PhysicalSourcePlanService.BoundKey("id","7",KeyValue.Type.DECIMAL)),admission.connectionVersion("c"));
        var source=new TraceRequest("c",null,null,30,TraceMode.CARDINALITY,null,null,context,receipt);
        var internal=receipts.resolve(source,"sqlserver");assertTrue(internal.fragmentTreeJson().contains("SELECT 7 AS id"));assertEquals("7",internal.keys().get(0).value());
        assertThrows(IllegalArgumentException.class,()->new TraceRequest("c",tree,request(tree).keys(),30,TraceMode.CARDINALITY,null,null,context,receipt));
        var c=config();when(connections.getById("c")).thenReturn(Optional.of(new ConnectionConfig(c.id(),c.name(),c.host(),c.port(),c.database(),c.username(),c.dialect(),"new-ref",c.readonly(),c.createdAt(),c.updatedAt(),c.connectionType(),c.trustServerCertificate())));
        denied("CONNECTION_CHANGED",()->receipts.resolve(source,"sqlserver"));
        when(connections.getById("c")).thenReturn(Optional.of(c));store.delete("s");
        denied("WORKSPACE_NOT_FOUND",()->receipts.resolve(source,"sqlserver"));
    }
    @Test void missingContextAndStrictRevisionCannotBypassAdmission() {
        denied("IDENTITY_CONTEXT_REQUIRED",()->admission.require(null,"c"));
        for(String value:List.of("-1","1.2","\"0\"","true","9223372036854775808"))assertThrows(Exception.class,
            ()->json.readValue("{\"sessionId\":\"s\",\"identityPolicy\":\"p\",\"workspaceRevision\":"+value+"}",DiagnosticContext.class));
    }
}
