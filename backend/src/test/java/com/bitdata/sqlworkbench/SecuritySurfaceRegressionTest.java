package com.bitdata.sqlworkbench;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Phase 1 acceptance (spec §4): the attack surface stays closed.
 * Sets a token so AuthTokenInterceptor runs in enforcing mode — unlike every
 * other suite, which relies on pass-through mode.
 */
@SpringBootTest(properties = "sql-workbench.auth.token=test-secret-token")
@AutoConfigureMockMvc
class SecuritySurfaceRegressionTest {

    @Autowired MockMvc mockMvc;
    @Autowired Environment environment;

    @Test
    void serverBindsToLoopbackOnly() {
        assertEquals("127.0.0.1", environment.getProperty("server.address"),
            "The backend must never listen on non-loopback interfaces");
    }

    @Test
    void healthEndpointIsExemptFromAuth() throws Exception {
        mockMvc.perform(get("/api/health"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void apiWithoutTokenIsRejected() throws Exception {
        mockMvc.perform(get("/api/connections"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void apiWithWrongTokenIsRejected() throws Exception {
        mockMvc.perform(get("/api/connections").header("X-Auth-Token", "wrong"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void apiWithValidTokenPasses() throws Exception {
        mockMvc.perform(get("/api/connections").header("X-Auth-Token", "test-secret-token"))
            .andExpect(status().isOk());
    }

    @Test
    void corsPreflightFromForeignOriginIsRejected() throws Exception {
        mockMvc.perform(options("/api/connections")
                .header("Origin", "https://evil.example")
                .header("Access-Control-Request-Method", "GET"))
            .andExpect(status().isForbidden());
    }

    @Test
    void corsPreflightFromRendererOriginIsAllowed() throws Exception {
        mockMvc.perform(options("/api/connections")
                .header("Origin", "file://")
                .header("Access-Control-Request-Method", "GET")
                .header("Access-Control-Request-Headers", "X-Auth-Token"))
            .andExpect(status().isOk())
            .andExpect(header().string("Access-Control-Allow-Origin", "file://"));
    }
}
