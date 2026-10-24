package com.bitdata.sqlworkbench.execution.model;

public record ExecutionRequest(
    String sql,
    String connectionId,
    Integer queryTimeoutSeconds,
    Integer maxRows,
    /** "estimated" or "actual". Null defaults to "estimated". */
    @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using = AnalyzeModeDeserializer.class)
    String analyzeMode,
    String sessionId
) {
    public ExecutionRequest(String sql,String connectionId,Integer queryTimeoutSeconds,Integer maxRows,String analyzeMode) {
        this(sql,connectionId,queryTimeoutSeconds,maxRows,analyzeMode,null);
    }
}
