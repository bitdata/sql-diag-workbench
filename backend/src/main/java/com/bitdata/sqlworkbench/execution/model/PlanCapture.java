package com.bitdata.sqlworkbench.execution.model;

import java.util.List;

@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
public record PlanCapture(int version,String requestedMode,String attemptedMode,String submittedSql,String explainSql,
        String connectionId,String sessionId,String dialect,String serverProduct,String serverVersion,String capturedAt,
        PlanCapabilities capabilities,String parseStatus,List<Issue> issues,PlanDetails details) {
    public PlanCapture { issues=List.copyOf(issues); }
    public PlanCapture(int version,String requestedMode,String attemptedMode,String submittedSql,String explainSql,
            String connectionId,String sessionId,String dialect,String serverProduct,String serverVersion,String capturedAt,
            PlanCapabilities capabilities,String parseStatus,List<Issue> issues) {
        this(version,requestedMode,attemptedMode,submittedSql,explainSql,connectionId,sessionId,dialect,serverProduct,serverVersion,capturedAt,capabilities,parseStatus,issues,null);
    }
    public PlanCapture withDetails(PlanDetails value) {return new PlanCapture(version,requestedMode,attemptedMode,submittedSql,explainSql,connectionId,sessionId,dialect,serverProduct,serverVersion,capturedAt,capabilities,parseStatus,issues,value);}
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
    public record Issue(String code,String nodePath,String message) {}
}
