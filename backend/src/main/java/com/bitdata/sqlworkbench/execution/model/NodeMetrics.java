package com.bitdata.sqlworkbench.execution.model;

@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
public record NodeMetrics(int version,String origin,String sourcePath,Metric estimatedRows,Metric actualRows,
        Metric actualDurationMs,Metric loops,Metric startupCost,Metric totalCost) {}
