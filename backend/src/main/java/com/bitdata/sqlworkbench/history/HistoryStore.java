package com.bitdata.sqlworkbench.history;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import com.bitdata.sqlworkbench.execution.model.ResultCompleteness;
import com.fasterxml.jackson.databind.ObjectMapper;

@Repository
public class HistoryStore {

    private final JdbcTemplate jdbc;
    private static final ObjectMapper JSON = new ObjectMapper();

    public HistoryStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<HistoryEntry> findBySessionId(String sessionId, int limit) {
        return jdbc.query(
            """
            SELECT id, session_id, sql_text, executed_at, duration_ms, row_count, result_completeness_json
            FROM history WHERE session_id = ?
            ORDER BY executed_at DESC LIMIT ?
            """,
            (rs, rowNum) -> new HistoryEntry(
                rs.getString("id"), rs.getString("session_id"), rs.getString("sql_text"),
                Instant.parse(rs.getString("executed_at")),
                rs.getObject("duration_ms", Integer.class),
                rs.getObject("row_count", Integer.class), decodeCompleteness(rs.getString("result_completeness_json"))
            ),
            sessionId, limit
        );
    }

    public List<HistoryEntry> findRecent(int limit) {
        return jdbc.query(
            """
            SELECT id, session_id, sql_text, executed_at, duration_ms, row_count, result_completeness_json
            FROM history ORDER BY executed_at DESC LIMIT ?
            """,
            (rs, rowNum) -> new HistoryEntry(
                rs.getString("id"), rs.getString("session_id"), rs.getString("sql_text"),
                Instant.parse(rs.getString("executed_at")),
                rs.getObject("duration_ms", Integer.class),
                rs.getObject("row_count", Integer.class), decodeCompleteness(rs.getString("result_completeness_json"))
            ),
            limit
        );
    }

    public HistoryEntry save(HistoryEntry entry) {
        jdbc.update(
            "INSERT INTO history (id, session_id, sql_text, executed_at, duration_ms, row_count, result_completeness_json) VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT(id) DO NOTHING",
            entry.id(), entry.sessionId(), entry.sqlText(), entry.executedAt().toString(),
            entry.durationMs(), entry.rowCount(), encodeCompleteness(entry.resultCompleteness())
        );
        var existing=jdbc.queryForMap("SELECT session_id,sql_text,duration_ms,row_count,result_completeness_json FROM history WHERE id=?",entry.id());
        if(!java.util.Objects.equals(existing.get("session_id"),entry.sessionId()) || !java.util.Objects.equals(existing.get("sql_text"),entry.sqlText())
            || !java.util.Objects.equals(existing.get("duration_ms"),entry.durationMs()) || !java.util.Objects.equals(existing.get("row_count"),entry.rowCount())
            || !decodeCompleteness((String)existing.get("result_completeness_json")).equals(entry.resultCompleteness()))
            throw new com.bitdata.sqlworkbench.storage.StorageService.Failure(409,"HISTORY_CONFLICT","This execution already has a different history record.");
        return entry;
    }

    private static ResultCompleteness decodeCompleteness(String value) {
        if (value == null) return ResultCompleteness.unknown();
        try {
            var node=JSON.readTree(value);
            if(node==null || !node.isObject() || !node.hasNonNull("state") ||
                    !java.util.Set.of("COMPLETE","TRUNCATED","UNKNOWN").contains(node.path("state").asText()) ||
                    (!node.path("state").asText().equals("UNKNOWN") &&
                        (!node.path("appliedLimit").isIntegralNumber() || node.path("appliedLimit").asLong()<1 ||
                         node.path("appliedLimit").asLong()>10000 || node.hasNonNull("errorReason"))))
                return new ResultCompleteness(ResultCompleteness.State.UNKNOWN,null,"HISTORY_COMPLETENESS_INVALID");
            return ResultCompleteness.normalize(JSON.treeToValue(node, ResultCompleteness.class));
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            return new ResultCompleteness(ResultCompleteness.State.UNKNOWN, null, "HISTORY_COMPLETENESS_INVALID");
        }
    }

    private static String encodeCompleteness(ResultCompleteness value) {
        try {
            return JSON.writeValueAsString(ResultCompleteness.normalize(value));
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new IllegalStateException("HISTORY_COMPLETENESS_SERIALIZATION_FAILED", failure);
        }
    }

    public int deleteOlderThan(int daysOld) {
        Instant threshold = Instant.now().minusSeconds(daysOld * 86400L);
        return jdbc.update("DELETE FROM history WHERE julianday(executed_at) < julianday(?)", threshold.toString());
    }
}
