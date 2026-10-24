package com.bitdata.sqlworkbench.fragment;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.hamcrest.Matchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class FragmentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void createUserEdit_validRequest_returnsEditResult() throws Exception {
        String requestJson = """
            {
                "sql": "SELECT * FROM orders WHERE status = 'active'",
                "parentFragmentId": "parent-abc",
                "dagContextIds": [],
                "dialect": "postgresql",
                "description": "test edit description"
            }
            """;

        mockMvc.perform(post("/api/fragment/create-user-edit")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestJson))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.classification").value("EDIT"))
            .andExpect(jsonPath("$.navigatedToExisting").value(false))
            .andExpect(jsonPath("$.node.id").isString())
            .andExpect(jsonPath("$.node.id", matchesPattern("[a-f0-9]{64}")))
            .andExpect(jsonPath("$.node.type").value("USER_EDITED"))
            .andExpect(jsonPath("$.node.label").value("test edit description"))
            .andExpect(jsonPath("$.node.editParentId").value("parent-abc"))
            .andExpect(jsonPath("$.node.canonicalSql").isNotEmpty())
            .andExpect(jsonPath("$.node.createdAt").isNotEmpty())
            .andExpect(jsonPath("$.node.parentIds", hasSize(0)));
    }

    @Test
    void createUserEdit_nullSql_returns400() throws Exception {
        String requestJson = """
            {
                "sql": null,
                "parentFragmentId": "parent-abc",
                "dagContextIds": [],
                "dialect": "postgresql"
            }
            """;

        mockMvc.perform(post("/api/fragment/create-user-edit")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestJson))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void createUserEdit_nullDialect_returns400() throws Exception {
        String requestJson = """
            {
                "sql": "SELECT 1",
                "parentFragmentId": "parent-abc",
                "dagContextIds": [],
                "dialect": null
            }
            """;

        mockMvc.perform(post("/api/fragment/create-user-edit")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestJson))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void createUserEdit_navigateCase() throws Exception {
        // First: create a node
        String firstRequest = """
            {
                "sql": "SELECT * FROM t WHERE a > 0",
                "parentFragmentId": "p1",
                "dagContextIds": [],
                "dialect": "postgresql",
                "description": null
            }
            """;

        var firstResult = mockMvc.perform(post("/api/fragment/create-user-edit")
                .contentType(MediaType.APPLICATION_JSON)
                .content(firstRequest))
            .andExpect(status().isOk())
            .andReturn();

        // Extract the node ID from the first response using Jackson
        String responseJson = firstResult.getResponse().getContentAsString();
        var mapper = new ObjectMapper();
        var tree = mapper.readTree(responseJson);
        String nodeId = tree.get("node").get("id").asText();

        // Second: submit equivalent SQL with the ID in dagContext
        String secondRequest = String.format("""
            {
                "sql": "select * from t where a > 0",
                "parentFragmentId": "p2",
                "dagContextIds": ["%s"],
                "dialect": "postgresql",
                "description": null
            }
            """, nodeId);

        mockMvc.perform(post("/api/fragment/create-user-edit")
                .contentType(MediaType.APPLICATION_JSON)
                .content(secondRequest))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.classification").value("NAVIGATE"))
            .andExpect(jsonPath("$.navigatedToExisting").value(true))
            .andExpect(jsonPath("$.node.id").value(nodeId));
    }

    @Test
    void createUserEdit_unsupportedShape_returns200WithUnsupportedClassification() throws Exception {
        // 契约：HTTP 保持 200（与既有 envelope 一致），降级靠 body 里的
        // classification + errorReason 表达（spec §4.2）。
        mockMvc.perform(post("/api/fragment/create-user-edit")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"sql":"UPDATE t SET a = 1","parentFragmentId":"parent-1",
                     "dagContextIds":[],"dialect":"postgresql","description":null}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.classification").value("UNSUPPORTED"))
            .andExpect(jsonPath("$.errorReason").isNotEmpty())
            .andExpect(jsonPath("$.node").doesNotExist());
    }
}
