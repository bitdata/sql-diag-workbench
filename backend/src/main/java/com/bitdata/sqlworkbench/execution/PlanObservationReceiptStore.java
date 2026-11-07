package com.bitdata.sqlworkbench.execution;

import com.bitdata.sqlworkbench.execution.model.ExecutionResult;
import com.bitdata.sqlworkbench.execution.model.PlanCapture;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Durable, short-lived copy of a server-verified Analyze result. */
@Repository
public class PlanObservationReceiptStore {
    public static final int MAX_BYTES = 4 * 1024 * 1024;
    public static final long MAX_AGE_HOURS = 24;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final TransactionTemplate tx;

    @org.springframework.beans.factory.annotation.Autowired
    public PlanObservationReceiptStore(JdbcTemplate jdbc, ObjectMapper mapper) {
        this(jdbc, mapper, Clock.systemUTC());
    }

    PlanObservationReceiptStore(JdbcTemplate jdbc, ObjectMapper mapper, Clock clock) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.clock = clock;
        this.tx = new TransactionTemplate(new DataSourceTransactionManager(Objects.requireNonNull(jdbc.getDataSource())));
    }

    public record Payload(int version, String id, String executionId, String sessionId, String sessionName,
                          String connectionId, String dialect, String submittedSql, String submittedSqlSha256,
                          String capturedAt, String createdAt, String expiresAt, String requestedMode,
                          ExecutionResult.PlanNode plan, PlanCapture planCapture, boolean degraded, String errorReason) {}
    public record Summary(String id, String executionId, String sessionId, String sessionName, String connectionId,
                          String dialect, String capturedAt, String createdAt, String expiresAt, int byteSize,
                          String payloadSha256, boolean finalized, String availability, String errorReason) {}
    public record Page(List<Summary> items, String nextCursor) {}
    public record Result(String status, String receiptId, String errorReason) {}
    private record ObservationIdentity(int version, String executionId, String sessionId, String connectionId,
                                       String dialect, String submittedSqlSha256, ExecutionResult.PlanNode plan,
                                       PlanCapture planCapture, boolean degraded, String errorReason) {}

    public Result save(ExecutionResult result, String sessionId, String connectionId, String dialect) {
        try { return saveVerified(result, sessionId, connectionId, dialect); }
        catch (Exception failure) { return new Result("FAILED", null, "SNAPSHOT_RECEIPT_PERSISTENCE_FAILED"); }
    }

    private Result saveVerified(ExecutionResult result, String sessionId, String connectionId, String dialect) {
        if (result == null || result.status() == null || !result.status().isTerminal()
                || result.status() != com.bitdata.sqlworkbench.execution.model.ExecutionStatus.COMPLETED
                || result.plan() == null || result.planCapture() == null) {
            return new Result("NOT_APPLICABLE", null, "PLAN_OBSERVATION_NOT_COMPLETE");
        }
        PlanCapture capture = result.planCapture();
        if (result.executionId() == null || result.executionId().isBlank() || capture.submittedSql() == null
                || capture.capturedAt() == null || capture.version() != 1
                || !Objects.equals(capture.connectionId(), connectionId)
                || !Objects.equals(capture.sessionId(), sessionId)
                || !Objects.equals(capture.dialect(), dialect)) {
            return new Result("FAILED", null, "SNAPSHOT_SCOPE_MISMATCH");
        }
        Instant created = clock.instant();
        String now = created.toString();
        String expires = created.plus(MAX_AGE_HOURS, ChronoUnit.HOURS).toString();
        String id = UUID.randomUUID().toString();
        String sessionName = sessionId == null ? null : jdbc.query("SELECT name FROM sessions WHERE id=?", rs -> rs.next() ? rs.getString(1) : null, sessionId);
        String sqlHash = sha256(capture.submittedSql());
        String observationHash;
        try {
            observationHash = sha256(mapper.writeValueAsString(new ObservationIdentity(1, result.executionId(), sessionId,
                    connectionId, dialect, sqlHash, result.plan(), capture, result.degraded(), result.errorReason())));
        } catch (JsonProcessingException failure) {
            return new Result("FAILED", null, "SNAPSHOT_ENCODING_FAILED");
        }
        Payload payload = new Payload(1, id, result.executionId(), sessionId, sessionName, connectionId, dialect,
                capture.submittedSql(), sqlHash, capture.capturedAt(), now, expires, capture.requestedMode(),
                result.plan(), capture, result.degraded(), result.errorReason());
        final String json;
        final int bytes;
        try {
            json = mapper.writeValueAsString(payload);
            bytes = json.getBytes(StandardCharsets.UTF_8).length;
        } catch (JsonProcessingException failure) {
            return new Result("FAILED", null, "SNAPSHOT_ENCODING_FAILED");
        }
        if (bytes > MAX_BYTES) return new Result("FAILED", null, "SNAPSHOT_BUDGET_EXCEEDED");
        String payloadHash = sha256(json);
        try {
            return tx.execute(status -> {
                jdbc.update("UPDATE storage_generation SET revision=revision+1 WHERE id=1");
                jdbc.update("INSERT OR IGNORE INTO plan_observation_receipts(id,execution_id,session_id,connection_id,dialect,sql_sha256,observation_sha256,payload_json,payload_sha256,byte_size,created_at,expires_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                        id, result.executionId(), sessionId, connectionId, dialect, sqlHash, observationHash, json, payloadHash, bytes, now, expires);
                var row = jdbc.queryForMap("SELECT id,observation_sha256 FROM plan_observation_receipts WHERE execution_id=? AND sql_sha256=?", result.executionId(), sqlHash);
                String existingId = String.valueOf(row.get("id"));
                String existingHash = String.valueOf(row.get("observation_sha256"));
                if (!existingHash.equals(observationHash)) return new Result("FAILED", null, "SNAPSHOT_REQUEST_CONFLICT");
                return new Result("SAVED", existingId, null);
            });
        } catch (Exception failure) {
            return new Result("FAILED", null, "SNAPSHOT_RECEIPT_PERSISTENCE_FAILED");
        }
    }

    public Page page(String cursor, int requestedLimit) {
        int limit = Math.max(1, Math.min(50, requestedLimit));
        String afterCreated = null, afterId = null;
        if (cursor != null && !cursor.isBlank()) {
            try {
                String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
                int split = decoded.indexOf('\n');
                if (split < 1) throw new IllegalArgumentException();
                afterCreated = decoded.substring(0, split); afterId = decoded.substring(split + 1);
            } catch (Exception invalid) { throw new Failure(400, "INVALID_CURSOR"); }
        }
        final String c = afterCreated, i = afterId;
        String now = clock.instant().toString();
        List<Summary> rows = c == null
            ? jdbc.query("SELECT * FROM plan_observation_receipts WHERE finalized_snapshot_id IS NULL AND julianday(expires_at)>julianday(?) ORDER BY created_at DESC,id DESC LIMIT ?", (rs,n)->summary(rs), now, limit+1)
            : jdbc.query("SELECT * FROM plan_observation_receipts WHERE finalized_snapshot_id IS NULL AND julianday(expires_at)>julianday(?) AND (created_at<? OR (created_at=? AND id<?)) ORDER BY created_at DESC,id DESC LIMIT ?", (rs,n)->summary(rs), now, c,c,i,limit+1);
        boolean more = rows.size() > limit;
        if (more) rows = new ArrayList<>(rows.subList(0, limit));
        String next = more && !rows.isEmpty() ? Base64.getUrlEncoder().withoutPadding().encodeToString((rows.get(rows.size()-1).createdAt()+"\n"+rows.get(rows.size()-1).id()).getBytes(StandardCharsets.UTF_8)) : null;
        return new Page(List.copyOf(rows), next);
    }

    public Payload get(String id) {
        var rows = jdbc.query("SELECT payload_json,payload_sha256,expires_at FROM plan_observation_receipts WHERE id=?", (rs,n)->new Object[]{rs.getString(1),rs.getString(2),rs.getString(3)}, id);
        if (rows.isEmpty()) throw new Failure(404, "SNAPSHOT_OBSERVATION_UNAVAILABLE");
        Object[] row = rows.get(0);
        return decode(id, (String)row[0], (String)row[1], (String)row[2]);
    }

    private Payload decode(String id, String json, String hash, String expiresAt) {
        try {
            if (json.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES || !sha256(json).equals(hash)) throw new Failure(409, "SNAPSHOT_CORRUPT");
            if (!clock.instant().isBefore(Instant.parse(expiresAt))) throw new Failure(410, "SNAPSHOT_OBSERVATION_EXPIRED");
            Payload p = mapper.readValue(json, Payload.class);
            validatePayload(p);
            if (!Objects.equals(p.id(), id) || !Objects.equals(p.expiresAt(), expiresAt)) throw new Failure(409, "SNAPSHOT_CORRUPT");
            if (!clock.instant().isBefore(Instant.parse(p.createdAt()).plus(MAX_AGE_HOURS,ChronoUnit.HOURS))) throw new Failure(410,"SNAPSHOT_OBSERVATION_EXPIRED");
            return p;
        }
        catch (Failure failure) { throw failure; }
        catch (Exception failure) { throw new Failure(409, "SNAPSHOT_CORRUPT"); }
    }

    /** Also used for archived receipts: expiry limits sealing, never offline reading. */
    static void validatePayload(Payload p) {
        if (p == null) throw new Failure(409, "SNAPSHOT_CORRUPT");
        if (p.version() != 1 || p.planCapture() != null && p.planCapture().version() != 1)
            throw new Failure(409, "SNAPSHOT_VERSION_UNSUPPORTED");
        try {
            PlanCapture capture=p.planCapture();
            if (p.id()==null || p.id().isBlank() || p.executionId()==null || p.executionId().isBlank()
                    || p.submittedSql()==null || p.connectionId()==null || p.dialect()==null || capture==null
                    || !Objects.equals(p.submittedSql(),capture.submittedSql())
                    || !Objects.equals(p.connectionId(),capture.connectionId()) || !Objects.equals(p.sessionId(),capture.sessionId())
                    || !Objects.equals(p.dialect(),capture.dialect()) || !Objects.equals(p.capturedAt(),capture.capturedAt())
                    || !Objects.equals(p.requestedMode(),capture.requestedMode())
                    || !Set.of("estimated","actual").contains(p.requestedMode())
                    || !Objects.equals(p.submittedSqlSha256(),sha256(p.submittedSql()))) throw new IllegalArgumentException();
            Instant created=Instant.parse(p.createdAt()), expires=Instant.parse(p.expiresAt());
            Instant.parse(p.capturedAt());
            // v1 originally sampled the clock twice. Its stored expiry can be a few
            // microseconds beyond createdAt+24h; cap effective availability in decode.
            if (!expires.isAfter(created)) throw new IllegalArgumentException();
            var nodes=new ArrayDeque<ExecutionResult.PlanNode>(); nodes.add(Objects.requireNonNull(p.plan()));
            while(!nodes.isEmpty()) { var node=nodes.remove(); if(node.operation()==null) throw new IllegalArgumentException(); nodes.addAll(node.children()); }
        } catch (Exception invalid) { throw new Failure(409, "SNAPSHOT_CORRUPT"); }
    }

    public void discard(String id) {
        tx.executeWithoutResult(status -> {
            jdbc.update("UPDATE storage_generation SET revision=revision+1 WHERE id=1");
            jdbc.update("DELETE FROM plan_observation_receipts WHERE id=? AND finalized_snapshot_id IS NULL", id);
        });
    }

    private Summary summary(java.sql.ResultSet rs) throws java.sql.SQLException {
        String sessionId = rs.getString("session_id");
        Payload payload = null; String error = null;
        try { payload = decode(rs.getString("id"),rs.getString("payload_json"),rs.getString("payload_sha256"),rs.getString("expires_at")); }
        catch (Failure unavailable) { error = unavailable.code; }
        String sessionName = payload == null ? null : payload.sessionName();
        return new Summary(rs.getString("id"), rs.getString("execution_id"), sessionId, sessionName,
                rs.getString("connection_id"), rs.getString("dialect"), payload == null ? null : payload.capturedAt(), rs.getString("created_at"),
                rs.getString("expires_at"), rs.getInt("byte_size"), rs.getString("payload_sha256"),
                rs.getString("finalized_snapshot_id") != null, error == null ? "AVAILABLE" : "UNAVAILABLE", error);
    }

    public static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception failure) { throw new IllegalStateException("SHA-256 unavailable", failure); }
    }

    public static class Failure extends RuntimeException {
        public final int status; public final String code;
        public Failure(int status, String code) { super(code); this.status=status; this.code=code; }
    }
}
