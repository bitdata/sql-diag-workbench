package com.bitdata.sqlworkbench.diagnostics;

import com.bitdata.sqlworkbench.connection.ConnectionService;
import com.bitdata.sqlworkbench.connection.DatabaseStrategy;
import com.bitdata.sqlworkbench.connection.DatabaseStrategyFactory;
import com.bitdata.sqlworkbench.sql.SelectOnlyGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.sql.*;

/**
 * Executes verification SQL to confirm or refute a diagnostic finding.
 * Uses direct JDBC (same pattern as RowTraceEngine) for synchronous execution.
 */
@Component
public class FindingVerifier {

    private static final Logger log = LoggerFactory.getLogger(FindingVerifier.class);
    private static final int VERIFY_TIMEOUT_SECONDS = 10;

    private final ConnectionService connectionService;
    private final DatabaseStrategyFactory strategyFactory;
    private final SelectOnlyGuard selectOnlyGuard;

    public FindingVerifier(ConnectionService connectionService,
                           DatabaseStrategyFactory strategyFactory,
                           SelectOnlyGuard selectOnlyGuard) {
        this.connectionService = connectionService;
        this.strategyFactory = strategyFactory;
        this.selectOnlyGuard = selectOnlyGuard;
    }

    public record VerifyResult(FindingStatus status, String summary, String errorReason, boolean degraded) {
        public VerifyResult(FindingStatus status,String summary) { this(status,summary,null,false); }
    }

    /**
     * Execute verification SQL and return CONFIRMED/REFUTED/SUSPECTED.
     *
     * @param sql          the verification SQL to execute
     * @param connectionId the database connection to use
     * @return result with status and summary message
     */
    public VerifyResult verify(String sql, String connectionId) {
        try {
            String dialect = connectionService.getById(connectionId)
                .map(c -> c.dialect())
                .orElse("postgresql");
            DatabaseStrategy strategy = strategyFactory.get(dialect);

            // S5 — validate the verification SQL as a single SELECT BEFORE
            // touching the database; wrap with the dialect-specific limit.
            selectOnlyGuard.requireSingleSelect(sql, dialect);
            String wrappedSql = strategy.wrapVerificationSql(sql);

            try (Connection conn = connectionService.openJdbcConnection(connectionId)) {
                if (strategy.supportsReadOnly()) {
                    strategy.configureReadOnly(conn);
                }

                try (PreparedStatement stmt = conn.prepareStatement(wrappedSql)) {
                    stmt.setQueryTimeout(VERIFY_TIMEOUT_SECONDS);
                    try (ResultSet rs = stmt.executeQuery()) {
                        if (rs.next()) {
                            int count = rs.getInt(1);
                            if (count > 0) {
                                return new VerifyResult(FindingStatus.CONFIRMED,
                                    count + " row(s) found — issue confirmed");
                            } else {
                                return new VerifyResult(FindingStatus.REFUTED,
                                    "0 rows — issue not reproduced");
                            }
                        }
                        return new VerifyResult(FindingStatus.REFUTED,
                            "0 rows — issue not reproduced");
                    }
                }
            }
        } catch (com.bitdata.sqlworkbench.connection.CredentialFailure e) {
            return new VerifyResult(FindingStatus.SUSPECTED,e.getMessage(),e.code,true);
        } catch (Exception e) {
            log.warn("Verification failed for connection {}: {}", connectionId, e.getMessage());
            return new VerifyResult(FindingStatus.SUSPECTED, "Error: " + e.getMessage());
        }
    }
}
