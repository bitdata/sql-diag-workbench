package com.bitdata.sqlworkbench.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * S1b — per-launch token auth for /api/**. The Electron main process
 * generates a random token at startup and passes it both to this backend
 * (JVM arg, see electron/backend-launcher.ts) and to the renderer (preload
 * IPC). Requests must carry it in the X-Auth-Token header.
 *
 * <p>When the token property is empty (unit-test slices, @SpringBootTest
 * suites that don't set it), the interceptor passes through and logs one
 * warning at construction — the Electron launcher ALWAYS sets a token, so
 * the shipped product is never in pass-through mode.
 */
@Component
public class AuthTokenInterceptor implements HandlerInterceptor {

    public static final String HEADER_NAME = "X-Auth-Token";
    private static final Logger log = LoggerFactory.getLogger(AuthTokenInterceptor.class);

    private final String expectedToken;

    public AuthTokenInterceptor(@Value("${sql-workbench.auth.token:}") String expectedToken) {
        this.expectedToken = expectedToken == null ? "" : expectedToken.trim();
        if (this.expectedToken.isEmpty()) {
            log.warn("sql-workbench.auth.token is not configured — /api/** is UNPROTECTED. "
                + "This pass-through mode exists for test contexts only; "
                + "the Electron launcher always generates and injects a token.");
        }
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if (expectedToken.isEmpty()) {
            return true;
        }
        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            return true;
        }
        String provided = request.getHeader(HEADER_NAME);
        if (provided != null && MessageDigest.isEqual(
                expectedToken.getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8))) {
            return true;
        }
        // The token value itself is never logged.
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(
            "{\"code\":\"UNAUTHORIZED\",\"message\":\"Missing or invalid " + HEADER_NAME + "\"}");
        return false;
    }
}
