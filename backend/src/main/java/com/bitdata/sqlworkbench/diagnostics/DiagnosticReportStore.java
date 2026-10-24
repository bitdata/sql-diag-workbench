package com.bitdata.sqlworkbench.diagnostics;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class DiagnosticReportStore {

    private final JdbcTemplate jdbc;

    public DiagnosticReportStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void save(DiagnosticReport report) {
        jdbc.update(
            """
            INSERT INTO diagnostic_reports (id, session_id, fragment_id, findings_json, created_at, evaluation_json)
            VALUES (?, ?, ?, ?, ?, ?)
            """,
            report.id(),
            report.sessionId(),
            report.fragmentId(),
            report.findingsJson(),
            report.createdAt().toString(), report.evaluationJson()
        );
    }

    public List<DiagnosticReport> findBySessionId(String sessionId) {
        return jdbc.query(
            """
            SELECT id, session_id, fragment_id, findings_json, created_at, evaluation_json
            FROM diagnostic_reports
            WHERE session_id = ?
            ORDER BY created_at DESC
            """,
            (rs, rowNum) -> new DiagnosticReport(
                rs.getString("id"),
                rs.getString("session_id"),
                rs.getString("fragment_id"),
                rs.getString("findings_json"),
                Instant.parse(rs.getString("created_at")), rs.getString("evaluation_json")
            ),
            sessionId
        );
    }

    public Optional<DiagnosticReport> findLatestBySessionAndFragment(String sessionId, String fragmentId) {
        var results = jdbc.query(
            """
            SELECT id, session_id, fragment_id, findings_json, created_at, evaluation_json
            FROM diagnostic_reports
            WHERE session_id = ? AND fragment_id = ?
            ORDER BY created_at DESC LIMIT 1
            """,
            (rs, rowNum) -> new DiagnosticReport(
                rs.getString("id"),
                rs.getString("session_id"),
                rs.getString("fragment_id"),
                rs.getString("findings_json"),
                Instant.parse(rs.getString("created_at")), rs.getString("evaluation_json")
            ),
            sessionId,
            fragmentId
        );
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }
}
