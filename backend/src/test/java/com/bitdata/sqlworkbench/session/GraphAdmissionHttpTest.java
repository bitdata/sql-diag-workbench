package com.bitdata.sqlworkbench.session;

import com.bitdata.sqlworkbench.audit.EditAuditStore;
import com.bitdata.sqlworkbench.sql.SqlParseService;
import com.bitdata.sqlworkbench.sql.SqlPositionService;
import com.bitdata.sqlworkbench.sql.diff.StructuralSqlCodec;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import java.nio.file.Files;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real Jackson/advice/controllers/SQLite; no external database or renderer-only guards. */
@SpringBootTest
@AutoConfigureMockMvc
class GraphAdmissionHttpTest {
    @DynamicPropertySource static void storage(DynamicPropertyRegistry properties) throws Exception {
        String dir = Files.createTempDirectory("graph-admission-").toString();
        properties.add("sql-workbench.data-dir", () -> dir);
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired SessionStore store;
    @SpyBean SqlParseService parser;
    @SpyBean SqlPositionService positions;
    @SpyBean EditAuditStore audit;
    Session current;
    static final String SQL = "SELECT * FROM t WHERE a > 0";
    static final List<String> ROUTES = List.of("/api/sql/parse", "/api/sql/decompose-options", "/api/sql/decompose",
        "/api/sql/dependencies", "/api/sql/positions", "/api/sql/join-diff", "/api/sql/fragment-diff",
        "/api/fragment/create-user-edit", "/api/diff", "/api/edit/classify");

    @BeforeEach void setup() {
        current = store.save(Session.builder().id(UUID.randomUUID().toString()).dialect("postgresql")
            .identityPolicy(StructuralSqlCodec.POLICY).sqlText(SQL).build());
        clearInvocations(parser,positions,audit);
    }
    ObjectNode request() {
        var form = StructuralSqlCodec.canonicalize(SQL,"postgresql");
        var body = json.createObjectNode();
        body.put("sql",SQL).put("sqlFragment",SQL).put("ruleName","clause-stripping")
            .put("sourceNodeId",form.id()).put("parentFragmentId",form.id())
            .put("before",SQL).put("after",SQL).put("parentSql",SQL).put("childSql",SQL)
            .put("type","where").put("dialect","postgresql").put("sessionId",current.id());
        body.set("dagContextIds",json.createArrayNode());
        body.set("identityContext",json.valueToTree(new IdentityContext(current.id(),current.identityPolicy(),current.workspaceRevision())));
        body.set("nodes",json.valueToTree(List.of(new NodeProof(form.id(),SQL,form.canonical()))));
        return body;
    }
    void deniedEverywhere(ObjectNode body,int status,String code) throws Exception {
        for (String route : ROUTES) {
            mvc.perform(post(route).contentType("application/json").content(json.writeValueAsBytes(body)))
                .andExpect(status().is(status)).andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.degraded").value(true));
        }
        verifyNoInteractions(parser,positions,audit);
        assertEquals(0,store.findById(current.id()).orElseThrow().workspaceRevision());
    }
    @Test void allTenRoutesAdmitCurrentContext() throws Exception {
        for (String route : ROUTES) mvc.perform(post(route).contentType("application/json")
            .content(json.writeValueAsBytes(request()))).andExpect(status().isOk());
    }
    @Test void missingContextCannotReachServicesOrAudit() throws Exception {
        var body=request(); body.remove("identityContext"); deniedEverywhere(body,400,"IDENTITY_CONTEXT_REQUIRED");
    }
    @Test void oldPolicyRemainsBytePreservedAndReadOnly() throws Exception {
        String old="{\"version\":1,\"original\":\"unchanged\"}";
        var legacy=store.save(Session.builder().id(UUID.randomUUID().toString()).workspaceSnapshotJson(old).fragmentTreeJson("old-tree").build());
        var body=request(); ((ObjectNode)body.get("identityContext")).put("sessionId",legacy.id());
        deniedEverywhere(body,409,"IDENTITY_POLICY_READ_ONLY");
        assertEquals(old,store.findById(legacy.id()).orElseThrow().workspaceSnapshotJson());
        assertEquals("old-tree",store.findById(legacy.id()).orElseThrow().fragmentTreeJson());
    }
    @Test void staleDeletedAndWrongDialectCannotReachServices() throws Exception {
        var body=request(); ((ObjectNode)body.get("identityContext")).put("workspaceRevision",1);
        deniedEverywhere(body,409,"WORKSPACE_REVISION_CONFLICT");
        body=request(); body.put("dialect","mysql"); deniedEverywhere(body,409,"WORKSPACE_DIALECT_MISMATCH");
        body=request(); ((ObjectNode)body.get("identityContext")).put("sessionId","deleted");
        deniedEverywhere(body,404,"WORKSPACE_NOT_FOUND");
    }
    @Test void fabricatedProofIsRejectedEvenWithCurrentContext() throws Exception {
        var body=request(); ((ObjectNode)body.get("nodes").get(0)).put("sqlFragment","SELECT 9");
        deniedEverywhere(body,409,"NODE_IDENTITY_MISMATCH");
    }
    @Test void strictJacksonRejectsRevisionCoercions() throws Exception {
        for (String invalid : List.of("-1","1.5","\"0\"","9223372036854775808")) {
            var body=request(); ((ObjectNode)body.get("identityContext")).set("workspaceRevision",json.readTree(invalid));
            for (String route : ROUTES) mvc.perform(post(route).contentType("application/json")
                .content(json.writeValueAsBytes(body))).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(parser,positions,audit);
    }
    @Test void sourceProofMustMatchDecompositionSql() throws Exception {
        var body=request(); body.put("sqlFragment","SELECT 2");
        mvc.perform(post("/api/sql/decompose").contentType("application/json").content(json.writeValueAsBytes(body)))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SOURCE_SQL_IDENTITY_MISMATCH"));
        verifyNoInteractions(parser,positions,audit);
    }
    @Test void saveCasReturnsNewRevisionAndPreservesWinningDraft() throws Exception {
        var body=json.createObjectNode().put("sqlText","SELECT 2").put("dialect","postgresql");
        body.putNull("connectionId").putNull("workspaceSnapshotJson");
        body.set("identityContext",request().get("identityContext"));
        String url="/api/sessions/"+current.id()+"/workspace";
        mvc.perform(put(url).contentType("application/json").content(json.writeValueAsBytes(body)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.workspaceRevision").value(1));
        body.put("sqlText","late draft");
        mvc.perform(put(url).contentType("application/json").content(json.writeValueAsBytes(body)))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("WORKSPACE_REVISION_CONFLICT"));
        assertEquals("SELECT 2",store.findById(current.id()).orElseThrow().sqlText());
    }
}
