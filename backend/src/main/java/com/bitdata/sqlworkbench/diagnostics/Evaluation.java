package com.bitdata.sqlworkbench.diagnostics;
import java.util.List;
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
public record Evaluation(int version,String executionId,String sourceState,List<RuleEvaluation> rules,boolean degraded,String errorReason) {
    public Evaluation { rules=List.copyOf(rules); }
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
    public record RuleEvaluation(String ruleId,String status,String errorReason) {}
}
