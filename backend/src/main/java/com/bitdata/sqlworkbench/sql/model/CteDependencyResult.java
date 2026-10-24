package com.bitdata.sqlworkbench.sql.model;

import java.util.List;

/**
 * Response model for CTE dependency analysis.
 */
public record CteDependencyResult(
    List<CteInfo> ctes,
    List<String> bodyDependsOn
) {
    public record CteInfo(
        String name,
        List<String> dependsOn
    ) {}
}