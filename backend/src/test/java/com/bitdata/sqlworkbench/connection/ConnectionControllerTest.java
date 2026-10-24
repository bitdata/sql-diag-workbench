package com.bitdata.sqlworkbench.connection;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pins the outer catch of {@code ConnectionController.test}: whatever escapes
 * {@link ConnectionService#test} must still come back as a classified result rather than a
 * 500 (CLAUDE.md rule #2) — this is the second and last {@code {"success":false,"error":…}}
 * site in {@code backend/src/main} (spec §6.3 same-class sweep).
 */
@WebMvcTest(ConnectionController.class)
class ConnectionControllerTest {

    @MockBean com.bitdata.sqlworkbench.execution.PlanCapabilityService planCapabilities;
    @Autowired MockMvc mockMvc;
    @MockBean ConnectionService service;
    @MockBean KeychainStore keychain;

    private static final String BODY = """
        {"name":"Test","host":"localhost","port":5432,"database":"testdb",\
        "username":"user","password":"pw","dialect":"postgresql"}
        """;

    @Test
    void test_outerCatchStillCarriesAnErrorReason() throws Exception {
        when(service.test(any())).thenThrow(new IllegalStateException("boom"));

        mockMvc.perform(post("/api/connections/test")
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.errorReason").value("other"))
            .andExpect(jsonPath("$.error").value("The connection test could not be confirmed. Retry the test."));
    }

    @Test
    void test_outerCatchSurvivesAnExceptionWithNoMessage() throws Exception {
        // Map.of rejects null values: unguarded, this becomes a 500 instead of a result.
        when(service.test(any())).thenThrow(new IllegalStateException());

        mockMvc.perform(post("/api/connections/test")
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.errorReason").value("other"))
            .andExpect(jsonPath("$.error").value("The connection test could not be confirmed. Retry the test."));
    }

    @Test
    void create_survivesAnExceptionWithNoMessage() throws Exception {
        // Same-class sweep (rule #1) INSIDE this file: create's catch builds the same
        // Map.of envelope 37 lines above the guarded one this iteration added. Leaving it
        // unguarded makes this PR's own change read as complete when it is not — a reader
        // who sees the guard below will infer the guard above.
        when(service.create(any())).thenThrow(new IllegalStateException());

        mockMvc.perform(post("/api/connections")
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.code").value("STORAGE_ERROR"))
            .andExpect(jsonPath("$.degraded").value(true));
    }
}
