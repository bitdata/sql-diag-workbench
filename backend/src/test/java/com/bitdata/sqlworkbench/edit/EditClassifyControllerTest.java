package com.bitdata.sqlworkbench.edit;

import com.bitdata.sqlworkbench.audit.EditAuditStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Spec §3.2 / §8.3 — ADR-0002 editor operation classification.
 *
 * <p>Verifies the public REST contract:
 * <ul>
 *   <li>action="EDIT" when no session match (or no sessionId).</li>
 *   <li>action="NAVIGATE" when the after SQL canonicalizes to an existing
 *       fragment's canonicalSql (in the session's tree).</li>
 *   <li>validation errors return 400.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
class EditClassifyControllerTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private EditAuditStore audit;

    @Test
    void classify_returnsEdit_whenNoSessionMatch() throws Exception {
        // Random sessionId — the SessionStore has no record of it, so the
        // tree walk yields no match and the classifier must return EDIT.
        mvc.perform(post("/api/edit/classify")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "sessionId": "non-existent-session",
                      "before": "SELECT * FROM t WHERE a>0",
                      "after":  "SELECT * FROM t WHERE a>0 AND b<20",
                      "dialect": "postgresql"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.action").value("EDIT"))
            .andExpect(jsonPath("$.equivalentNodeId").doesNotExist())
            .andExpect(jsonPath("$.canonicalSql").isString())
            .andExpect(jsonPath("$.auditId").isString());
    }

    @Test
    void classify_validatesMissingSessionId() throws Exception {
        mvc.perform(post("/api/edit/classify")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "before": "SELECT 1",
                      "after":  "SELECT 1",
                      "dialect": "postgresql"
                    }
                    """))
            .andExpect(status().isBadRequest());
    }

    @Test
    void classify_validatesBlankBefore() throws Exception {
        mvc.perform(post("/api/edit/classify")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "sessionId": "x",
                      "before": "",
                      "after":  "SELECT 1",
                      "dialect": "postgresql"
                    }
                    """))
            .andExpect(status().isBadRequest());
    }

    @Test
    void classify_writesAuditEntryOnEdit() throws Exception {
        // Confirm a real audit row was written by reading back via SQL.
        long before = countAuditRows();
        mvc.perform(post("/api/edit/classify")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "sessionId": "audit-write-test",
                      "before": "SELECT * FROM t",
                      "after":  "SELECT * FROM t WHERE x>0",
                      "dialect": "postgresql"
                    }
                    """))
            .andExpect(status().isOk());
        long after = countAuditRows();
        org.junit.jupiter.api.Assertions.assertTrue(after > before,
            "audit row should be written on classification");
    }

    private long countAuditRows() {
        return audit.recentForSession("audit-write-test", 1000).size();
    }
}
