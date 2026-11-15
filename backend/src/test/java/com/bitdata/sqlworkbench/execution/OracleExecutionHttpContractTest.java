package com.bitdata.sqlworkbench.execution;

import com.bitdata.sqlworkbench.security.AuthTokenInterceptor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Production controller, engine, lease manager and authentication; no database connections. */
class OracleExecutionHttpContractTest {
    private static final String TOKEN = "synthetic-http-contract-token";
    private static MockMvc http(ExecutionEngine engine) {
        return MockMvcBuilders.standaloneSetup(new ExecutionController(engine))
            .addInterceptors(new AuthTokenInterceptor(TOKEN)).build();
    }

    @Test void drainRequiresAuthenticationAndRefusesNewWorkWithoutAnExecutionId() throws Exception {
        try (var f = new OracleExecutionLifecycleTest.Fixture()) {
            var mvc = http(f.engine);
            for (var request : java.util.List.of(get("/api/execution/drain"), post("/api/execution/drain"))) {
                mvc.perform(request).andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
            }
            assertEquals(false, f.engine.shutdownSnapshot().get("stopping"));
            mvc.perform(post("/api/execution/drain").header("X-Auth-Token", TOKEN))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.stopping").value(true))
                .andExpect(jsonPath("$.drained").value(true)).andExpect(jsonPath("$.tasks").isEmpty());
            mvc.perform(get("/api/execution/drain").header("X-Auth-Token", TOKEN))
                .andExpect(status().isOk()).andExpect(jsonPath("$.drained").value(true));
            for (String operation : java.util.List.of("execute", "analyze")) {
                mvc.perform(post("/api/execution/" + operation).header("X-Auth-Token", TOKEN)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"sql\":\"SELECT 1 FROM dual\",\"connectionId\":\"synthetic\"}"))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("EXECUTION_SHUTTING_DOWN"))
                    .andExpect(jsonPath("$.errorReason").value("APPLICATION_SHUTDOWN"))
                    .andExpect(jsonPath("$.degraded").value(true))
                    .andExpect(jsonPath("$.executionId").doesNotExist());
            }
            assertEquals(0, f.opens.get());
            assertEquals(0, f.executes.get());
        }
    }

    @Test void typedGuardRefusalHasVersionedSettledLifecycleOverHttp() throws Exception {
        try (var f = new OracleExecutionLifecycleTest.Fixture()) {
            var mvc = http(f.engine);
            var response = mvc.perform(post("/api/execution/execute").header("X-Auth-Token", TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sql\":\"SELECT seq.NEXTVAL FROM dual\",\"connectionId\":\"synthetic\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            String id = new ObjectMapper().readTree(response).get("executionId").asText();
            OracleExecutionLifecycleTest.eventually(() -> (boolean)
                OracleExecutionLifecycleTest.lifecycle(f.engine, id).get("settled"));
            mvc.perform(get("/api/execution/execute/" + id).header("X-Auth-Token", TOKEN))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.errorReason").isString())
                .andExpect(jsonPath("$.lifecycle.version").value(1))
                .andExpect(jsonPath("$.lifecycle.revision").isNumber())
                .andExpect(jsonPath("$.lifecycle.settled").value(true));
            assertEquals(0, f.opens.get());
            verify(f.statement, never()).execute(org.mockito.ArgumentMatchers.anyString());
        }
    }

    @Test void oracleTraceRequiresIdentityAndVerificationRemainsUnsupported() throws Exception {
        var connections = org.mockito.Mockito.mock(com.bitdata.sqlworkbench.connection.ConnectionService.class);
        org.mockito.Mockito.when(connections.getById("synthetic"))
            .thenReturn(java.util.Optional.of(OracleExecutionLifecycleTest.config("oracle", "one")));
        var identity = org.mockito.Mockito.mock(com.bitdata.sqlworkbench.session.WorkspaceIdentity.class);
        org.mockito.Mockito.when(identity.require(null,"oracle")).thenThrow(
            new com.bitdata.sqlworkbench.session.WorkspaceIdentity.Failure(400,"IDENTITY_CONTEXT_REQUIRED"));
        var admission = new com.bitdata.sqlworkbench.diagnostics.trace.DiagnosticAdmission(identity, connections, new ObjectMapper());
        var refused = assertThrows(com.bitdata.sqlworkbench.session.WorkspaceIdentity.Failure.class,
            () -> admission.require(null, "synthetic"));
        assertEquals(400, refused.status);
        assertEquals("IDENTITY_CONTEXT_REQUIRED", refused.code);
        org.mockito.Mockito.verify(identity).require(null,"oracle");

        var verifier = new com.bitdata.sqlworkbench.diagnostics.FindingVerifier(connections, null, null);
        var verification = verifier.verify("SELECT 1 FROM dual", "synthetic");
        assertTrue(verification.degraded());
        assertEquals("SUSPECTED", verification.status().name());
        assertEquals("ORACLE_DIAGNOSTICS_UNSUPPORTED", verification.errorReason());
        verify(connections, never()).openJdbcConnection(org.mockito.ArgumentMatchers.anyString());
    }

    @Test void oraclePlanDiagnosticsRequireRetainedExecutionRatherThanBlanketDialectRefusal() {
        var engine = org.mockito.Mockito.mock(ExecutionEngine.class);
        var controller = new com.bitdata.sqlworkbench.diagnostics.DiagnosticsController(null, null, null, null, new ObjectMapper(), null);
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "executionEngine", engine);
        var missing = new IllegalArgumentException("retained execution unavailable");
        org.mockito.Mockito.when(engine.resolvePlanObservation("synthetic-run", "SELECT 1 FROM dual", "synthetic", "session"))
            .thenThrow(missing);
        var plan = new com.bitdata.sqlworkbench.diagnostics.DiagnosticsController.PlanRequest(
            "synthetic-run", "SELECT 1 FROM dual", "synthetic", "session");
        assertSame(missing, assertThrows(IllegalArgumentException.class, () -> controller.analyzePlan(plan)));
        verify(engine).resolvePlanObservation("synthetic-run", "SELECT 1 FROM dual", "synthetic", "session");
        var malformed = new com.bitdata.sqlworkbench.diagnostics.DiagnosticsController.AnalyzeRequest(null, null, null, null, null, null, "synthetic");
        assertThrows(IllegalArgumentException.class, () -> controller.analyze(malformed));
        org.mockito.Mockito.verifyNoMoreInteractions(engine);
    }
}
