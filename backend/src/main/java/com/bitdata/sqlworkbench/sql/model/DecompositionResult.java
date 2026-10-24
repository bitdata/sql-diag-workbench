package com.bitdata.sqlworkbench.sql.model;

import java.util.List;

/** Source-specific experiment evidence. Hashes refer to raw UTF-8 SQL, not identity. */
public final class DecompositionResult {
    private DecompositionResult() {}
    public record Issue(String code, String message, String ruleName, String scopePath,
                        String fragmentId, String sql) {}
    public record Change(String operation, String subject, String scopePath,
                         String beforeSql, String afterSql) {}
    public record Retained(String subject, String scopePath) {}
    public record Effect(String sourceSqlHash, String targetSqlHash, String context,
                         List<Change> changes, List<Retained> retained, List<Issue> limitations) {}
    public record Output(String sourceNodeId, String ruleName, String targetNodeId,
                         int ordinal, Effect effect) {}
    public record Batch(String status, List<FragmentNode> children, List<Output> outputs,
                        List<Issue> issues) {}
    public record Scan(String status, List<Issue> issues) {}
    public record Options(List<DecomposeOption> options, Scan scan) {}
}
