package com.bitdata.sqlworkbench.diagnostics;

import com.bitdata.sqlworkbench.execution.model.ExecutionResult.PlanNode;
import com.bitdata.sqlworkbench.execution.model.ExecutionResult.Timing;
import com.bitdata.sqlworkbench.sql.model.FragmentNode;
import com.bitdata.sqlworkbench.sql.model.FragmentType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class DiagnosticsControllerPersistenceTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void analyzePersistsReport() throws Exception {
        var fragment = new FragmentNode("f1", FragmentType.ORIGINAL, "root", "SELECT 1",
            true, null, 0, null, List.of());
        var plan = new PlanNode("Seq Scan", 0.0, 100.0, 100L, 50L, 10L, List.of());
        var timing = new Timing(100L);

        var request = new DiagnosticsController.AnalyzeRequest(
            fragment, plan, timing, null, null);

        // Analyze — should persist a report (or skip persistence when sessionId is null)
        mockMvc.perform(post("/api/diagnostics/analyze")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isOk());
    }

    @Test
    void analyzeWithSessionIdPersistsAndRetrievesReport() throws Exception {
        var fragment = new FragmentNode("f2", FragmentType.ORIGINAL, "root", "SELECT 1",
            true, null, 0, null, List.of());
        var plan = new PlanNode("Seq Scan", 0.0, 100.0, 100L, 50L, 10L, List.of());
        var timing = new Timing(100L);

        var request = new DiagnosticsController.AnalyzeRequest(
            fragment, plan, timing, "test-session-1", "f2");

        // Analyze with sessionId — should persist
        mockMvc.perform(post("/api/diagnostics/analyze")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isOk());

        // Verify report was persisted via the reports endpoint
        mockMvc.perform(get("/api/diagnostics/reports")
                .param("sessionId", "test-session-1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].fragmentId").value("f2"));
    }

    @Test
    void reportWriteFailureIsVisibleAndDoesNotDiscardFindings() throws Exception {
        var registry=org.mockito.Mockito.mock(RuleRegistry.class);
        var store=org.mockito.Mockito.mock(DiagnosticReportStore.class);
        var finding=DiagnosticFinding.builder().id("f").ruleId("rule").title("Finding").message("Details").build();
        org.mockito.Mockito.when(registry.evaluate(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any())).thenReturn(new RuleRegistry.Evaluated(List.of(finding),new Evaluation(1,null,"UNVERIFIED",List.of(),true,"UNVERIFIED_PLAN_SOURCE")));
        org.mockito.Mockito.doThrow(new RuntimeException("injected storage outage")).when(store).save(org.mockito.ArgumentMatchers.any());
        var controller=new DiagnosticsController(registry,null,null,store,objectMapper,null);
        var request=new DiagnosticsController.AnalyzeRequest(new FragmentNode("f",com.bitdata.sqlworkbench.sql.model.FragmentType.ORIGINAL,"root","SELECT 1",true,null,0,null,List.of()),
            new PlanNode("Seq Scan",0,0,0,0,0,List.of()),new Timing(0),"session","f");
        var response=controller.analyze(request);
        org.junit.jupiter.api.Assertions.assertEquals(List.of(finding),response.findings());
        org.junit.jupiter.api.Assertions.assertEquals("failed",response.reportPersistence().status());
        org.mockito.Mockito.verify(store).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void missingReportStoreReturnsObservableFailureWithoutLosingFindings() {
        var registry=org.mockito.Mockito.mock(RuleRegistry.class);
        var finding=DiagnosticFinding.builder().id("f").ruleId("rule").title("Finding").message("Details").build();
        org.mockito.Mockito.when(registry.evaluate(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any())).thenReturn(new RuleRegistry.Evaluated(List.of(finding),new Evaluation(1,null,"UNVERIFIED",List.of(),true,"UNVERIFIED_PLAN_SOURCE")));
        var controller=new DiagnosticsController(registry,null,null,null,objectMapper,null);
        var request=new DiagnosticsController.AnalyzeRequest(new FragmentNode("f",com.bitdata.sqlworkbench.sql.model.FragmentType.ORIGINAL,"root","SELECT 1",true,null,0,null,List.of()),
            new PlanNode("Seq Scan",0,0,0,0,0,List.of()),new Timing(0),"session","f");
        var response=controller.analyze(request);
        org.junit.jupiter.api.Assertions.assertEquals(List.of(finding),response.findings());
        org.junit.jupiter.api.Assertions.assertEquals("failed",response.reportPersistence().status());
    }


    @Test
    void getReportsReturnsEmptyForUnknownSession() throws Exception {
        mockMvc.perform(get("/api/diagnostics/reports")
                .param("sessionId", "nonexistent-session"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").isEmpty());
    }
}
