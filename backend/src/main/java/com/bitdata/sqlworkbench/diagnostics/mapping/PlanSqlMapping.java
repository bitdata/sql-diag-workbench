package com.bitdata.sqlworkbench.diagnostics.mapping;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/** Execution-scoped, additive mapping between physical plan nodes and SQL evidence. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record PlanSqlMapping(
    int version,
    String executionId,
    String captureKey,
    String dialect,
    String submittedSqlSha256,
    List<PlanMappingEdge> edges,
    boolean degraded,
    String errorReason,
    List<MappingIssue> issues
) {
    public PlanSqlMapping {
        edges = edges == null ? List.of() : List.copyOf(edges);
        issues = issues == null ? List.of() : List.copyOf(issues);
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record PlanMappingEdge(
        String planNodePath,
        TargetKind targetKind,
        String targetId,
        SqlSpan span,
        Confidence confidence,
        Relation relation,
        String reason
    ) {}

    public enum TargetKind { SQL_SPAN, FRAGMENT, DIAGNOSTIC_EVIDENCE }
    public enum Confidence { EXACT, INFERRED, UNKNOWN }
    public enum Relation { PLAN_TO_SQL, PLAN_TO_FRAGMENT, PLAN_TO_EVIDENCE }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record SqlSpan(String nodeId, String type, String label, int startOffset, int endOffset) {}

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record MappingIssue(String code, String planNodePath, String message) {}
}
