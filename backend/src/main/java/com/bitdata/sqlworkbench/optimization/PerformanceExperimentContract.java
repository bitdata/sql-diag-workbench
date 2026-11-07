package com.bitdata.sqlworkbench.optimization;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.bitdata.sqlworkbench.execution.model.PlanDetails;
import java.util.*;
import static com.bitdata.sqlworkbench.optimization.OptimizationSql.*;

/** Versioned PERF-04 wire records. Kept separate from the result-validation contract. */
public final class PerformanceExperimentContract {
    private PerformanceExperimentContract() {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Request(int version, Source source, String candidateSql, String candidateId, String seed, Integer timeoutSeconds) {}
    public record Submission(String experimentId) {}
    public record Protocol(String id, String seed, int warmupPairs, int measuredPairs, String orderAlgorithm, String pgPreparation, String mysqlPreparation, int fetchSize) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Validation(String verdict, String errorReason, String comparisonMode, Snapshot snapshot, Side original, Side candidate, List<Difference> differences, boolean differencesTruncated) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Sample(int pair, String phase, String order, String side, String status, String durationNs, String executeReturnNs, String drainNs, String closeNs, String rows, String bytes, String errorReason) {}
    public record Environment(Map<String,String> before, Map<String,String> after, boolean comparable, List<String> limitations) {}
    public record Distribution(String medianNs, String q1Ns, String q3Ns, String minNs, String maxNs) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Summary(String state, Distribution original, Distribution candidate, String pairedMedianDeltaNs, String relativeMedianChangePct, List<String> notes) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ActualPlanEvidence(String status, String dialect, String channel, String connectionRevision, String submittedSql,
                                     String explainSql, String capturedAt, String databaseProduct,
                                     String databaseVersion, String sessionIdentity, PlanDetails details,
                                     String errorReason, boolean degraded) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ActualPlans(ActualPlanEvidence original, ActualPlanEvidence candidate,
                              boolean degraded, String errorReason) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Persistence(String status, String errorReason) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Report(int version, String experimentId, String status, String phase, String comparisonState, String errorReason, boolean degraded,
                         Request request, Protocol protocol, Validation validation, Environment environment, List<Sample> samples,
                         Summary summary, Persistence persistence, ActualPlans actualPlans,
                         String createdAt, String startedAt, String completedAt) {}
    public record HistoryItem(int version, String experimentId, String completedAt, String resultVerdict, String comparisonState, String originalPreview, String candidatePreview) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Page(int version, List<HistoryItem> items, String nextCursor) {}
}
