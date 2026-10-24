package com.bitdata.sqlworkbench.sql.diff;

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
                .content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ops.length()").value(1))
            .andExpect(jsonPath("$.ops[0].kind").value("MOD"))
            .andExpect(jsonPath("$.ops[0].nodeType").value("EXPR_LESS_THAN_OR_EQUAL"))
            .andExpect(jsonPath("$.ops[0].oldText").value("b<20"))
            .andExpect(jsonPath("$.ops[0].newText").value("b<=20"));
    }

    @Test
    void diff_returnsEmptyOpsForIdenticalSql() throws Exception {
        String sql = "SELECT * FROM t WHERE a>0";
        String body = String.format(
            "{\"before\":\"%s\",\"after\":\"%s\",\"dialect\":\"postgresql\"}", sql, sql);
        mvc.perform(post("/api/diff")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ops.length()").value(0));
    }

    @Test
    void diff_validatesMissingBefore() throws Exception {
        mvc.perform(post("/api/diff")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"after\":\"SELECT 1\",\"dialect\":\"postgresql\"}"))
            .andExpect(status().isBadRequest());
    }
}
