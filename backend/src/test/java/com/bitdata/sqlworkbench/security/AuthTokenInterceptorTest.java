package com.bitdata.sqlworkbench.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.*;

class AuthTokenInterceptorTest {

    private MockHttpServletRequest request(String method, String token) {
        MockHttpServletRequest req = new MockHttpServletRequest(method, "/api/connections");
        if (token != null) {
            req.addHeader(AuthTokenInterceptor.HEADER_NAME, token);
        }
        return req;
    }

    @Test
    void passesThroughWhenTokenNotConfigured() throws Exception {
        AuthTokenInterceptor interceptor = new AuthTokenInterceptor("");
        assertTrue(interceptor.preHandle(request("GET", null), new MockHttpServletResponse(), new Object()),
            "Empty configured token = test/bare mode: pass through (launcher always sets a token)");
    }

    @Test
    void rejectsMissingHeader() throws Exception {
        AuthTokenInterceptor interceptor = new AuthTokenInterceptor("secret");
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(request("GET", null), response, new Object()));
        assertEquals(401, response.getStatus());
        assertTrue(response.getContentAsString().contains("UNAUTHORIZED"));
    }

    @Test
    void rejectsWrongToken() throws Exception {
        AuthTokenInterceptor interceptor = new AuthTokenInterceptor("secret");
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(request("GET", "other"), response, new Object()));
        assertEquals(401, response.getStatus());
    }

    @Test
    void acceptsMatchingToken() throws Exception {
        AuthTokenInterceptor interceptor = new AuthTokenInterceptor("secret");
        assertTrue(interceptor.preHandle(request("GET", "secret"), new MockHttpServletResponse(), new Object()));
    }

    @Test
    void optionsPreflightBypassesAuth() throws Exception {
        AuthTokenInterceptor interceptor = new AuthTokenInterceptor("secret");
        assertTrue(interceptor.preHandle(request("OPTIONS", null), new MockHttpServletResponse(), new Object()),
            "CORS preflight cannot carry custom headers");
    }
}
