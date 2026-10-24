package com.bitdata.sqlworkbench.diagnostics;

import java.util.List;

/**
 * A diagnostic finding produced by a {@link DiagnosticRule}.
 *
 * @param id               unique identifier for this finding
 * @param ruleId           the rule that produced this finding
 * @param severity         severity level (HIGH / MEDIUM / LOW)
 * @param title            one-line summary, e.g. "Large row estimate mismatch"
 * @param message          detailed explanation for the user
 * @param evidence         list of specific evidence facts supporting the finding
 * @param confidence       0.0–1.0, how certain the rule is that this is a real issue
 * @param verificationSql  SQL snippet that can be run to confirm the issue (may be null)
 * @param affectedNodeId     which fragment node this finding relates to (may be null)
 * @param status             lifecycle status (defaults to {@link FindingStatus#SUSPECTED})
 * @param verificationResult result message from verification SQL execution (may be null)
 */
public record DiagnosticFinding(
    String id,
    String ruleId,
    DiagnosticRule.Severity severity,
    String title,
    String message,
    List<String> evidence,
    double confidence,
    String verificationSql,
    String affectedNodeId,
    FindingStatus status,
    String verificationResult,
    boolean degraded,
    String errorReason
) {
    public DiagnosticFinding(String id, String ruleId, DiagnosticRule.Severity severity, String title, String message,
                             List<String> evidence, double confidence, String verificationSql, String affectedNodeId,
                             FindingStatus status, String verificationResult) {
        this(id, ruleId, severity, title, message, evidence, confidence, verificationSql, affectedNodeId,
             status, verificationResult, false, null);
    }
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String id;
        private String ruleId;
        private DiagnosticRule.Severity severity = DiagnosticRule.Severity.MEDIUM;
        private String title = "";
        private String message = "";
        private List<String> evidence = List.of();
        private double confidence = 1.0;
        private String verificationSql;
        private String affectedNodeId;
        private FindingStatus status = FindingStatus.SUSPECTED;
        private String verificationResult;
        private boolean degraded;
        private String errorReason;

        public Builder id(String id) { this.id = id; return this; }
        public Builder ruleId(String ruleId) { this.ruleId = ruleId; return this; }
        public Builder severity(DiagnosticRule.Severity severity) { this.severity = severity; return this; }
        public Builder title(String title) { this.title = title; return this; }
        public Builder message(String message) { this.message = message; return this; }
        public Builder evidence(List<String> evidence) { this.evidence = evidence; return this; }
        public Builder confidence(double confidence) { this.confidence = confidence; return this; }
        public Builder verificationSql(String verificationSql) { this.verificationSql = verificationSql; return this; }
        public Builder affectedNodeId(String affectedNodeId) { this.affectedNodeId = affectedNodeId; return this; }
        public Builder status(FindingStatus status) { this.status = status; return this; }
        public Builder verificationResult(String verificationResult) { this.verificationResult = verificationResult; return this; }
        public Builder degraded(boolean value) { degraded = value; return this; }
        public Builder errorReason(String value) { errorReason = value; return this; }

        public DiagnosticFinding build() {
            return new DiagnosticFinding(id, ruleId, severity, title, message, evidence, confidence, verificationSql, affectedNodeId, status, verificationResult, degraded, errorReason);
        }
    }
}
