package com.bitdata.sqlworkbench.execution.model;

@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
public record PlanCapabilities(String connectionId,String dialect,String serverProduct,String serverVersion,
        ModeCapability estimated,ModeCapability actual,String observedAt,boolean degraded,String errorReason) {
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
    public record ModeCapability(String state,String errorReason) {}
    public boolean supports(String mode) { return "SUPPORTED".equals(("actual".equals(mode)?actual:estimated).state()); }
}
