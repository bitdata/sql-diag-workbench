package com.bitdata.sqlworkbench.optimization;

public final class OptimizationFailure extends RuntimeException {
    public final int status;
    public final String code, reason;
    public OptimizationFailure(int status, String code, String reason) {
        super(reason); this.status=status; this.code=code; this.reason=reason;
    }
    static OptimizationFailure invalid(String reason) { return new OptimizationFailure(400,"OPTIMIZATION_VALIDATION_ERROR",reason); }
    static OptimizationFailure changed(String reason) { return new OptimizationFailure(409,"OPTIMIZATION_SOURCE_CHANGED",reason); }
}
