package com.bitdata.sqlworkbench.config;

import com.bitdata.sqlworkbench.security.AuthTokenInterceptor;
import org.junit.jupiter.api.Test;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AppConfigCorsTest {

    /** Exposes the protected getCorsConfigurations() for assertions. */
    private static class InspectableCorsRegistry extends CorsRegistry {
        Map<String, CorsConfiguration> configs() {
            return getCorsConfigurations();
        }
    }

    @Test
    void corsRestrictsOriginsAndDisablesCredentials() {
        AppConfig config = new AppConfig(new AuthTokenInterceptor("test-token"));
        InspectableCorsRegistry registry = new InspectableCorsRegistry();
        config.corsConfigurer().addCorsMappings(registry);

        CorsConfiguration cors = registry.configs().get("/api/**");
        assertNotNull(cors, "CORS must be registered for /api/**");
        // Spring normalizes the origin pattern "file://" to "file:/" when storing
        // it; Task 12's SecuritySurfaceRegressionTest verifies end-to-end that an
        // actual Origin: file:// preflight is still allowed.
        assertEquals(List.of("file:/", "http://localhost:*"), cors.getAllowedOriginPatterns(),
            "Origins must be limited to the Electron renderer (packaged=file://, dev=localhost)");
        assertEquals(Boolean.FALSE, cors.getAllowCredentials(),
            "Token header auth replaces cookies — credentials must be disabled");
        assertTrue(cors.getAllowedHeaders().contains("Content-Type"));
        assertTrue(cors.getAllowedHeaders().contains("X-Auth-Token"));
        assertTrue(cors.getAllowedMethods().containsAll(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS")));
    }
}
