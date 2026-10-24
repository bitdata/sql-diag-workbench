package com.bitdata.sqlworkbench.connection;

import java.util.Map;

/** Safe, stable error boundary. Never include the native/driver exception text. */
public class CredentialFailure extends RuntimeException {
    public final String code;
    public final int status;
    public CredentialFailure(String code, int status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }
    public Map<String,Object> envelope() {
        return Map.of("code", code, "errorReason", code, "message", getMessage(), "degraded", true);
    }
}
