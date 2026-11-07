package com.bitdata.sqlworkbench.diagnostics;

import com.bitdata.sqlworkbench.diagnostics.mapping.PlanSqlMapping;
import com.bitdata.sqlworkbench.diagnostics.mapping.PlanSqlMappingService;
import com.bitdata.sqlworkbench.diagnostics.trace.RowTraceEngine;
import com.bitdata.sqlworkbench.diagnostics.trace.TraceStore;
import com.bitdata.sqlworkbench.execution.ExecutionEngine;
import com.bitdata.sqlworkbench.execution.model.ExecutionResult;
import com.bitdata.sqlworkbench.execution.model.ExecutionStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(DiagnosticsController.class)
class DiagnosticsControllerPlanMappingTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockBean ExecutionEngine executionEngine;
    @MockBean PlanSqlMappingService mappingService;
    @MockBean RuleRegistry ruleRegistry;
    @MockBean RowTraceEngine traceEngine;
    @MockBean TraceStore traceStore;
    @MockBean DiagnosticReportStore reportStore;
    @MockBean FindingVerifier findingVerifier;

    @Test
    void forwardsExecutionScopeAndPostgresDialectToMappingService() throws Exception {
        var result = verifiedResult("exec-pg");
        when(executionEngine.resolvePlanObservation("exec-pg", "SELECT * FROM orders", "conn-pg", "session-1"))
            .thenReturn(new ExecutionEngine.PlanObservation("VERIFIED", null, result));
        when(mappingService.map(eq("exec-pg"), eq("postgresql"), eq("SELECT * FROM orders"), same(result), isNull(), anyList()))
            .thenReturn(mapping("exec-pg", "postgresql", false, null));

        mockMvc.perform(post("/api/diagnostics/plan-mapping")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"executionId":"exec-pg","sql":"SELECT * FROM orders","dialect":"postgresql",
                     "connectionId":"conn-pg","sessionId":"session-1","fragment":null,"findings":[]}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.executionId").value("exec-pg"))
            .andExpect(jsonPath("$.dialect").value("postgresql"))
            .andExpect(jsonPath("$.degraded").value(false));

        verify(executionEngine).resolvePlanObservation("exec-pg", "SELECT * FROM orders", "conn-pg", "session-1");
        verify(mappingService).map(eq("exec-pg"), eq("postgresql"), eq("SELECT * FROM orders"), same(result), isNull(), anyList());
    }

    @Test
    void preservesMysqlDialectAndReturnsObservableDegradationWhenPlanIsUnavailable() throws Exception {
        when(executionEngine.resolvePlanObservation("exec-mysql", "SELECT * FROM orders", "conn-mysql", null))
            .thenReturn(new ExecutionEngine.PlanObservation("UNAVAILABLE", "PLAN_SOURCE_UNAVAILABLE", null));
        when(mappingService.map(eq("exec-mysql"), eq("mysql"), eq("SELECT * FROM orders"), isNull(), isNull(), anyList()))
            .thenReturn(mapping("exec-mysql", "mysql", true, "PLAN_SOURCE_UNAVAILABLE"));

        mockMvc.perform(post("/api/diagnostics/plan-mapping")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"executionId":"exec-mysql","sql":"SELECT * FROM orders","dialect":"mysql",
                     "connectionId":"conn-mysql","findings":[]}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.executionId").value("exec-mysql"))
            .andExpect(jsonPath("$.dialect").value("mysql"))
            .andExpect(jsonPath("$.degraded").value(true))
            .andExpect(jsonPath("$.errorReason").value("PLAN_SOURCE_UNAVAILABLE"));

        verify(mappingService).map(eq("exec-mysql"), eq("mysql"), eq("SELECT * FROM orders"), isNull(), isNull(), anyList());
    }

    @Test
    void rejectsMissingExecutionScopeBeforeCallingEngine() throws Exception {
        mockMvc.perform(post("/api/diagnostics/plan-mapping")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sql\":\"SELECT 1\",\"connectionId\":\"conn-1\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.degraded").value(true))
            .andExpect(jsonPath("$.errorReason").value("VALIDATION_ERROR"));

        verifyNoInteractions(executionEngine, mappingService);
    }

    private static ExecutionResult verifiedResult(String executionId) {
        var plan = new ExecutionResult.PlanNode("Seq Scan", 0, 1, 1, 1, 1, List.of(), "orders",
            ExecutionResult.PlanNodeConditions.EMPTY);
        return new ExecutionResult(executionId, ExecutionStatus.COMPLETED, List.of(), List.of(), 1,
            new ExecutionResult.Timing(1), plan, null, null, null, null, false);
    }

    private static PlanSqlMapping mapping(String executionId, String dialect, boolean degraded, String reason) {
        return new PlanSqlMapping(1, executionId, "capture", dialect, "hash", List.of(), degraded, reason, List.of());
    }
}
