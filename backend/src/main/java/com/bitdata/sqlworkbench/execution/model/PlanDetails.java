package com.bitdata.sqlworkbench.execution.model;

import java.util.*;

/** Additive, independently versioned evidence. Older captures have no details. */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
public record PlanDetails(int version,String channel,String rawPlan,String database,String databaseUser,
        Map<String,String> settings,Metric planningTimeMs,Metric executionTimeMs,List<NodeEvidence> nodes,String errorReason) {
    public PlanDetails {settings=settings==null?Map.of():Map.copyOf(settings);nodes=nodes==null?List.of():List.copyOf(nodes);}
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
    public record NodeEvidence(String sourcePath,String raw,Metric firstRowMs) {}
}
