package com.bitdata.sqlworkbench.diagnostics;

import com.bitdata.sqlworkbench.diagnostics.trace.RowTraceEngine;
import com.bitdata.sqlworkbench.diagnostics.trace.TraceStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(DiagnosticsController.class)
class DiagnosticsControllerVerifyTest {

    @MockBean com.bitdata.sqlworkbench.execution.ExecutionEngine executionEngine;
    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockBean RuleRegistry ruleRegistry;
    @MockBean RowTraceEngine traceEngine;
    @MockBean TraceStore traceStore;
    @MockBean DiagnosticReportStore reportStore;
    @MockBean FindingVerifier findingVerifier;

    @Test
    void verifyReturnsConfirmed() throws Exception {
        when(findingVerifier.verify("SELECT 1 WHERE true", "conn-1"))
            .thenReturn(new FindingVerifier.VerifyResult(FindingStatus.CONFIRMED, "1 row(s) found — issue confirmed"));

        String body = """
            {"findingId":"f-1","verificationSql":"SELECT 1 WHERE true","connectionId":"conn-1"}
            """;

        mockMvc.perform(post("/api/diagnostics/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.findingId").value("f-1"))
            .andExpect(jsonPath("$.status").value("CONFIRMED"))
            .andExpect(jsonPath("$.resultSummary").value("1 row(s) found — issue confirmed"));
    }

    @Test
    void verifyReturnsRefuted() throws Exception {
        when(findingVerifier.verify("SELECT 1 WHERE false", "conn-1"))
            .thenReturn(new FindingVerifier.VerifyResult(FindingStatus.REFUTED, "0 rows — issue not reproduced"));

        String body = """
            {"findingId":"f-2","verificationSql":"SELECT 1 WHERE false","connectionId":"conn-1"}
            """;

        mockMvc.perform(post("/api/diagnostics/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("REFUTED"));
    }

    @Test
    void verifyReturnsBadRequestForEmptySql() throws Exception {
        String body = """
            {"findingId":"f-3","verificationSql":"","connectionId":"conn-1"}
            """;

        mockMvc.perform(post("/api/diagnostics/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isBadRequest());
    }

    @Test
    void verifyReturnsBadRequestForMissingConnectionId() throws Exception {
        String body = """
            {"findingId":"f-4","verificationSql":"SELECT 1","connectionId":""}
            """;

        mockMvc.perform(post("/api/diagnostics/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isBadRequest());
    }
}
