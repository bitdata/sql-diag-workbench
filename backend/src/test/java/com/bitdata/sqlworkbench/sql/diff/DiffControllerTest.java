package com.bitdata.sqlworkbench.sql.diff;

import com.bitdata.sqlworkbench.session.GraphHttpFixtures;
import com.bitdata.sqlworkbench.session.SessionStore;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class DiffControllerTest {

    @Autowired SessionStore sessions;
    String admit(String body) throws Exception { return GraphHttpFixtures.admit(body,sessions); }

    @Autowired
    private MockMvc mvc;

    @Test
    void diff_returnsSingleModForB20ToBLe20() throws Exception {
        String body = """
            {
              "before": "SELECT * FROM t WHERE a>0 AND b<20",
              "after":  "SELECT * FROM t WHERE a>0 AND b<=20",
              "dialect": "postgresql"
            }
            """;
        mvc.perform(post("/api/diff")
                .contentType(MediaType.APPLICATION_JSON)
                .content(admit(body)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ops.length()").value(1))
            .andExpect(jsonPath("$.ops[0].kind").value("MOD"))
            .andExpect(jsonPath("$.ops[0].nodeType").value("EXPRESSION"))
            .andExpect(jsonPath("$.ops[0].displayScope").value("AST_NODE"))
            .andExpect(jsonPath("$.ops[0].oldText").value("B < 20"))
            .andExpect(jsonPath("$.ops[0].newText").value("B <= 20"));
    }

    @Test
    void diff_returnsEmptyOpsForIdenticalSql() throws Exception {
        String sql = "SELECT * FROM t WHERE a>0";
        String body = String.format(
            "{\"before\":\"%s\",\"after\":\"%s\",\"dialect\":\"postgresql\"}", sql, sql);
        mvc.perform(post("/api/diff")
                .contentType(MediaType.APPLICATION_JSON)
                .content(admit(body)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ops.length()").value(0));
    }

    @Test
    void diff_validatesMissingBefore() throws Exception {
        mvc.perform(post("/api/diff")
                .contentType(MediaType.APPLICATION_JSON)
                .content(admit("{\"after\":\"SELECT 1\",\"dialect\":\"postgresql\"}")))
            .andExpect(status().isBadRequest());
    }
}
