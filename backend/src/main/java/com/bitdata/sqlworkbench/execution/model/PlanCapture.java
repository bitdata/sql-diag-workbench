package com.bitdata.sqlworkbench.execution.model;

import java.util.List;

@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
public record PlanCapture(int version,String requestedMode,String attemptedMode,String submittedSql,String explainSql,
        String connectionId,String sessionId,String dialect,String serverProduct,String serverVersion,String capturedAt,
        PlanCapabilities capabilities,String parseStatus,List<Issue> issues) {
    public PlanCapture { issues=List.copyOf(issues); }
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
    public record Issue(String code,String nodePath,String message) {}
}
