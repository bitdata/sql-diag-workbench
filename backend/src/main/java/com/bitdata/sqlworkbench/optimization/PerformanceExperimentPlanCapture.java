package com.bitdata.sqlworkbench.optimization;

import com.bitdata.sqlworkbench.connection.DatabaseStrategy;
import com.bitdata.sqlworkbench.connection.DatabaseStrategyFactory;
import com.bitdata.sqlworkbench.execution.ExplainService;
import com.bitdata.sqlworkbench.execution.ExplainServiceFactory;
import com.bitdata.sqlworkbench.execution.PlanEvidence;
import com.bitdata.sqlworkbench.execution.model.PlanCapture;
import com.bitdata.sqlworkbench.execution.model.PlanDetails;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static com.bitdata.sqlworkbench.optimization.PerformanceExperimentContract.ActualPlanEvidence;
import static com.bitdata.sqlworkbench.optimization.PerformanceExperimentContract.ActualPlans;

/** Captures actual plans after measured samples, on the experiment's already leased snapshot connection. */
@Component
final class PerformanceExperimentPlanCapture {
    private final DatabaseStrategyFactory strategies;
    private final ExplainServiceFactory adapters;

    PerformanceExperimentPlanCapture(DatabaseStrategyFactory strategies, ExplainServiceFactory adapters) {
        this.strategies = strategies;
        this.adapters = adapters;
    }

    ActualPlans capture(Connection connection, String dialect, String originalSql, String candidateSql,
                        String connectionRevision, ValidationControl control) {
        ActualPlanEvidence original = captureOne(connection, dialect, originalSql, connectionRevision, control);
        ActualPlanEvidence candidate = captureOne(connection, dialect, candidateSql, connectionRevision, control);
        boolean degraded = original.degraded() || candidate.degraded();
        String reason = degraded ? "ACTUAL_PLAN_EVIDENCE_INCOMPLETE" : null;
        return new ActualPlans(original, candidate, degraded, reason);
    }

    private ActualPlanEvidence captureOne(Connection connection, String dialect, String sql, String connectionRevision,
                                          ValidationControl control) {
        String channel = "postgresql".equals(dialect) ? "POSTGRESQL_JSON" : "MYSQL_TREE";
        DatabaseStrategy strategy = strategies.get(dialect);
        String explainSql = strategy.getAnalyzeSql(sql);
        String capturedAt = Instant.now().toString();
        try {
            control.check();
            String raw;
            try (Statement statement = connection.createStatement()) {
                control.attach(statement);
                try (ResultSet result = statement.executeQuery(explainSql)) {
                    if (result.getMetaData().getColumnCount() != 1 || !result.next()) {
                        return failed(dialect, channel, connectionRevision, sql, explainSql, capturedAt, "ACTUAL_PLAN_RESULT_SHAPE_UNSUPPORTED");
                    }
                    raw = result.getString(1);
                    if (result.next()) return failed(dialect, channel, connectionRevision, sql, explainSql, capturedAt, "ACTUAL_PLAN_RESULT_SHAPE_UNSUPPORTED");
                }
            } finally {
                control.detach(null);
            }
            control.check();
            String product = connection.getMetaData().getDatabaseProductName();
            String version = connection.getMetaData().getDatabaseProductVersion();
            String sessionIdentity = sessionIdentity(connection, dialect);
            PlanEvidence.Environment environment = PlanEvidence.environment(connection, dialect, remainingSeconds(control));
            ExplainService.Parsed parsed = adapters.get(dialect).parseDetailed(raw, dialect, channel);
            PlanDetails details = PlanEvidence.capture(raw, channel, parsed.plan(), environment);
            String reason = details.errorReason();
            if (reason == null && !parsed.issues().isEmpty()) reason = parsed.issues().get(0).code();
            boolean degraded = !"COMPLETE".equals(parsed.status()) || reason != null;
            return new ActualPlanEvidence(parsed.status(), dialect, channel, connectionRevision, sql, explainSql, capturedAt,
                product, version, sessionIdentity, details, reason, degraded);
        } catch (Exception failure) {
            String reason = control.stopReason() == null ? "ACTUAL_PLAN_CAPTURE_FAILED" : control.stopReason();
            return failed(dialect, channel, connectionRevision, sql, explainSql, capturedAt, reason);
        }
    }

    private String sessionIdentity(Connection connection, String dialect) throws SQLException {
        String sql = "postgresql".equals(dialect) ? "SELECT pg_backend_pid()" : "SELECT CONNECTION_ID()";
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            if (!result.next()) throw new SQLException("SESSION_IDENTITY_UNAVAILABLE");
            return result.getString(1);
        }
    }

    private int remainingSeconds(ValidationControl control) {
        long remaining = control.deadline - System.nanoTime();
        if (remaining <= 0) throw OptimizationSql.reject("DEADLINE_EXCEEDED");
        return Math.max(1, (int) Math.min(300, (remaining + 999_999_999L) / 1_000_000_000L));
    }

    private ActualPlanEvidence failed(String dialect, String channel, String connectionRevision, String sql, String explainSql,
                                     String capturedAt, String reason) {
        var missing = new com.bitdata.sqlworkbench.execution.model.Metric("NOT_COLLECTED", null,
            "MILLISECONDS", "TOTAL", "ACTUAL_PLAN", reason);
        PlanDetails details = new PlanDetails(1, channel, null, null, null, Map.of(), missing, missing, List.of(), reason);
        return new ActualPlanEvidence("FAILED", dialect, channel, connectionRevision, sql, explainSql, capturedAt,
            null, null, null, details, reason, true);
    }
}
