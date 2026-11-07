package com.bitdata.sqlworkbench.execution;

import com.bitdata.sqlworkbench.diagnostics.DiagnosticFinding;
import com.bitdata.sqlworkbench.diagnostics.Evaluation;
import com.bitdata.sqlworkbench.diagnostics.mapping.PlanSqlMapping;
import com.bitdata.sqlworkbench.diagnostics.mapping.PlanSqlMappingService;
import com.bitdata.sqlworkbench.execution.model.ExecutionResult;
import com.bitdata.sqlworkbench.execution.model.ExecutionStatus;
import com.bitdata.sqlworkbench.sql.model.FragmentNode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.*;

/** Creates and reads immutable, offline plan investigation snapshots. */
@Service
public class InvestigationSnapshotStore {
    public static final int MAX_BYTES = 4 * 1024 * 1024;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final PlanObservationReceiptStore receipts;
    private final PlanSqlMappingService mappings;
    private final Clock clock;
    private final TransactionTemplate tx;

    @org.springframework.beans.factory.annotation.Autowired
    public InvestigationSnapshotStore(JdbcTemplate jdbc, ObjectMapper mapper,
            PlanObservationReceiptStore receipts, PlanSqlMappingService mappings) {
        this(jdbc, mapper, receipts, mappings, Clock.systemUTC());
    }

    InvestigationSnapshotStore(JdbcTemplate jdbc, ObjectMapper mapper, PlanObservationReceiptStore receipts,
            PlanSqlMappingService mappings, Clock clock) {
        this.jdbc = jdbc; this.mapper = mapper.copy().enable(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS); this.receipts = receipts; this.mappings = mappings; this.clock = clock;
        this.tx = new TransactionTemplate(new DataSourceTransactionManager(Objects.requireNonNull(jdbc.getDataSource())));
    }

    public record Annotation(String title, String hypothesis, String observation) {}
    public record CreateRequest(String observationId, String requestId, String sessionId, Annotation annotation,
            FragmentNode workspace, Evaluation evaluation, List<DiagnosticFinding> findings,
            boolean sensitiveDataConfirmed) {}
    public record Section<T>(String state, String reason, T value) {}
    public record Diagnostics(Evaluation evaluation, List<DiagnosticFinding> findings) {
        public Diagnostics { findings = findings == null ? List.of() : List.copyOf(findings); }
    }
    public record SnapshotSource(String sessionId, String sessionName, String executionId, String connectionId,
            String dialect, String serverProduct, String serverVersion, String capturedAt, String requestedMode,
            String submittedSql, String submittedSqlSha256) {}
    public record Content(int version, String id, String createdAt, String sourceObservationId, Annotation annotation,
            SnapshotSource source, PlanObservationReceiptStore.Payload observation,
            Section<FragmentNode> workspace, Section<Diagnostics> diagnostics,
            Section<PlanSqlMapping> mapping) {}
    public record Integrity(int version, String payloadSha256) {}
    public record Snapshot(Content content, Integrity integrity) {}
    public record Summary(String id, String createdAt, String title, String sessionId, String sessionName,
            String dialect, String serverProduct, String serverVersion, String executionId,
            boolean partial, boolean degraded, boolean pinned, long byteSize, String availability, String errorReason) {}
    public record Page(List<Summary> items, String nextCursor) {}
    public record CreateResult(String status, String snapshotId, long byteSize) {}

    public CreateResult create(CreateRequest request) {
        validate(request);
        final String requestHash;
        try {
            requestHash = PlanObservationReceiptStore.sha256(mapper.writeValueAsString(new RequestFingerprint(
                    request.observationId(), request.sessionId(), request.annotation(), request.workspace(),
                    request.evaluation(), request.findings(), request.sensitiveDataConfirmed())));
        } catch (JsonProcessingException failure) {
            throw new Failure(400, "SNAPSHOT_ENCODING_FAILED");
        }
        return tx.execute(status -> {
            jdbc.update("UPDATE storage_generation SET revision=revision WHERE id=1");
            var previous = jdbc.query("SELECT request_sha256,snapshot_id FROM investigation_snapshot_requests WHERE request_id=?",
                    (rs,n) -> new String[]{rs.getString(1), rs.getString(2)}, request.requestId());
            if (!previous.isEmpty()) {
                if (!previous.get(0)[0].equals(requestHash)) throw new Failure(409, "SNAPSHOT_REQUEST_CONFLICT");
                if (snapshotBytes(previous.get(0)[1]) == 0) throw new Failure(410, "SNAPSHOT_TARGET_GONE");
                return new CreateResult("SAVED", previous.get(0)[1], snapshotBytes(previous.get(0)[1]));
            }

            PlanObservationReceiptStore.Payload receipt;
            try { receipt = receipts.get(request.observationId()); }
            catch (PlanObservationReceiptStore.Failure failure) { throw new Failure(failure.status, failure.code); }
            if (receipt.version() != 1) throw new Failure(409, "SNAPSHOT_VERSION_UNSUPPORTED");
            if (Instant.parse(receipt.expiresAt()).isBefore(clock.instant())) throw new Failure(410, "SNAPSHOT_OBSERVATION_UNAVAILABLE");
            if (receipt.plan() == null || receipt.planCapture() == null) throw new Failure(409, "SNAPSHOT_PLAN_UNAVAILABLE");
            if (request.sessionId() != null && !Objects.equals(request.sessionId(), receipt.sessionId()))
                throw new Failure(409, "SNAPSHOT_SCOPE_MISMATCH");
            if (request.workspace() != null && (receipt.sessionId() == null
                    || !Objects.equals(request.sessionId(), receipt.sessionId())
                    || !Objects.equals(receipt.submittedSql(), request.workspace().sqlFragment())))
                throw new Failure(409, "SNAPSHOT_WORKSPACE_SCOPE_MISMATCH");
            if (request.workspace() != null) {
                var owners=jdbc.queryForList("SELECT sql_text,dialect,connection_id FROM sessions WHERE id=?",receipt.sessionId());
                if (!owners.isEmpty() && (!Objects.equals(owners.get(0).get("dialect"),receipt.dialect())
                        || !Objects.equals(owners.get(0).get("connection_id"),receipt.connectionId())))
                    throw new Failure(409,"SNAPSHOT_WORKSPACE_SCOPE_MISMATCH");
            }
            if (request.evaluation() != null && (request.workspace() == null
                    || !Objects.equals(receipt.executionId(), request.evaluation().executionId())))
                throw new Failure(409, "SNAPSHOT_DIAGNOSTICS_SCOPE_MISMATCH");
            if (request.findings() != null && !request.findings().isEmpty() && request.evaluation() == null)
                throw new Failure(409, "SNAPSHOT_DIAGNOSTICS_SCOPE_MISMATCH");

            var existing = jdbc.query("SELECT finalized_snapshot_id FROM plan_observation_receipts WHERE id=?",
                    (rs,n) -> rs.getString(1), request.observationId());
            if (existing.isEmpty()) throw new Failure(410, "SNAPSHOT_OBSERVATION_UNAVAILABLE");
            if (existing.get(0) != null) throw new Failure(409, "SNAPSHOT_OBSERVATION_ALREADY_FINALIZED");

            String id = UUID.randomUUID().toString();
            String createdAt = clock.instant().toString();
            SnapshotSource source = new SnapshotSource(receipt.sessionId(), receipt.sessionName(), receipt.executionId(),
                    receipt.connectionId(), receipt.dialect(), receipt.planCapture().serverProduct(),
                    receipt.planCapture().serverVersion(), receipt.capturedAt(), receipt.requestedMode(),
                    receipt.submittedSql(), receipt.submittedSqlSha256());
            Section<FragmentNode> workspace = request.workspace() == null
                    ? new Section<>("NOT_AVAILABLE", "WORKSPACE_NOT_CAPTURED", null)
                    : new Section<>("AVAILABLE", null, request.workspace());
            Section<Diagnostics> diagnostics = request.evaluation() == null
                    ? new Section<>("NOT_AVAILABLE", "DIAGNOSTICS_NOT_CAPTURED", null)
                    : new Section<>(request.evaluation().degraded() || hasDegradedFinding(request.findings()) ? "DEGRADED" : "AVAILABLE",
                        request.evaluation().errorReason(), new Diagnostics(request.evaluation(), request.findings()));
            Section<PlanSqlMapping> mapping;
            if (request.workspace() == null) {
                mapping = new Section<>("NOT_AVAILABLE", "MAPPING_REQUIRES_WORKSPACE", null);
            } else {
                ExecutionResult result = new ExecutionResult(receipt.executionId(), ExecutionStatus.COMPLETED,
                        null, null, 0, null, receipt.plan(), null, null, null, receipt.errorReason(),
                        receipt.degraded(), null, Instant.parse(receipt.capturedAt()), receipt.planCapture(), null);
                PlanSqlMapping mapped = mappings.map(receipt.executionId(), receipt.dialect(), receipt.submittedSql(),
                        result, request.workspace(), request.evaluation() == null ? List.of() : request.findings());
                if (!Objects.equals(mapped.executionId(),receipt.executionId()) || !Objects.equals(mapped.dialect(),receipt.dialect())
                        || !Objects.equals(mapped.submittedSqlSha256(),receipt.submittedSqlSha256()))
                    throw new Failure(409, "SNAPSHOT_MAPPING_SCOPE_MISMATCH");
                mapping = new Section<>(mapped.degraded() ? "DEGRADED" : "AVAILABLE", mapped.errorReason(), mapped);
            }
            Content content = new Content(1, id, createdAt, request.observationId(), request.annotation(), source,
                    receipt, workspace, diagnostics, mapping);
            validateContent(content);
            String payload;
            try {
                String contentJson = mapper.writeValueAsString(content);
                String payloadHash = PlanObservationReceiptStore.sha256(contentJson);
                payload = mapper.writeValueAsString(new Snapshot(content, new Integrity(1, payloadHash)));
            } catch (JsonProcessingException failure) { throw new Failure(400, "SNAPSHOT_ENCODING_FAILED"); }
            long bytes = payload.getBytes(StandardCharsets.UTF_8).length;
            if (bytes > MAX_BYTES) throw new Failure(413, "SNAPSHOT_BUDGET_EXCEEDED");
            String payloadHash = PlanObservationReceiptStore.sha256(mapperWrite(content));
            jdbc.update("UPDATE storage_generation SET revision=revision+1 WHERE id=1");
            jdbc.update("INSERT INTO investigation_snapshots(id,version,source_observation_id,session_id,created_at,snapshot_json,payload_sha256,byte_size) VALUES(?,?,?,?,?,?,?,?)",
                    id, 1, request.observationId(), receipt.sessionId(), createdAt, payload, payloadHash, bytes);
            int finalized = jdbc.update("UPDATE plan_observation_receipts SET finalized_snapshot_id=? WHERE id=? AND finalized_snapshot_id IS NULL",
                    id, request.observationId());
            if (finalized != 1) throw new Failure(409, "SNAPSHOT_OBSERVATION_ALREADY_FINALIZED");
            jdbc.update("INSERT INTO investigation_snapshot_requests(request_id,request_sha256,snapshot_id) VALUES(?,?,?)",
                    request.requestId(), requestHash, id);
            return new CreateResult("SAVED", id, bytes);
        });
    }

    private record RequestFingerprint(String observationId, String sessionId, Annotation annotation,
            FragmentNode workspace, Evaluation evaluation, List<DiagnosticFinding> findings, boolean confirmed) {}

    private String mapperWrite(Content content) {
        try { return mapper.writeValueAsString(content); }
        catch (JsonProcessingException failure) { throw new Failure(400, "SNAPSHOT_ENCODING_FAILED"); }
    }

    private long snapshotBytes(String id) {
        Long bytes = jdbc.query("SELECT byte_size FROM investigation_snapshots WHERE id=?", rs -> rs.next() ? rs.getLong(1) : null, id);
        return bytes == null ? 0 : bytes;
    }

    public Page page(String cursor, int requestedLimit) {
        int limit = Math.max(1, Math.min(50, requestedLimit));
        String afterCreated = null, afterId = null;
        if (cursor != null && !cursor.isBlank()) {
            try {
                String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
                int split = decoded.indexOf('\n'); if (split < 1) throw new IllegalArgumentException();
                afterCreated = decoded.substring(0, split); afterId = decoded.substring(split + 1);
            } catch (Exception invalid) { throw new Failure(400, "INVALID_CURSOR"); }
        }
        String sql = "SELECT s.id,s.created_at,s.snapshot_json,s.byte_size,s.payload_sha256,s.version,EXISTS(SELECT 1 FROM investigation_snapshot_protection p WHERE p.snapshot_id=s.id) AS pinned FROM investigation_snapshots s";
        List<Object> args = new ArrayList<>();
        if (afterCreated != null) { sql += " WHERE (s.created_at<? OR (s.created_at=? AND s.id<?))"; args.add(afterCreated); args.add(afterCreated); args.add(afterId); }
        sql += " ORDER BY s.created_at DESC,s.id DESC LIMIT ?"; args.add(limit + 1);
        List<Summary> rows = jdbc.query(sql, (rs,n) -> summary(rs.getString("id"), rs.getString("created_at"),
                rs.getString("snapshot_json"), rs.getString("payload_sha256"), rs.getInt("version"), rs.getLong("byte_size"), rs.getBoolean("pinned")), args.toArray());
        boolean more = rows.size() > limit;
        if (more) rows = new ArrayList<>(rows.subList(0, limit));
        String next = more && !rows.isEmpty() ? Base64.getUrlEncoder().withoutPadding().encodeToString(
                (rows.get(rows.size()-1).createdAt()+"\n"+rows.get(rows.size()-1).id()).getBytes(StandardCharsets.UTF_8)) : null;
        return new Page(List.copyOf(rows), next);
    }

    public Snapshot get(String id) {
        var rows = jdbc.query("SELECT snapshot_json,payload_sha256,version,byte_size,created_at FROM investigation_snapshots WHERE id=?",
                (rs,n) -> new Object[]{rs.getString(1), rs.getString(2), rs.getInt(3),rs.getLong(4),rs.getString(5)}, id);
        if (rows.isEmpty()) throw new Failure(404, "SNAPSHOT_UNAVAILABLE");
        Object[] row = rows.get(0);
        return decode(id,(String)row[4],(String)row[0],(String)row[1],(int)row[2],(long)row[3]);
    }

    private Snapshot decode(String id,String createdAt,String json,String storedHash,int version,long bytes) {
        if(version!=1) throw new Failure(409,"SNAPSHOT_VERSION_UNSUPPORTED");
        try {
            if(json.getBytes(StandardCharsets.UTF_8).length!=bytes || bytes>MAX_BYTES) throw new Failure(409,"SNAPSHOT_CORRUPT");
            Snapshot snapshot=mapper.readValue(json,Snapshot.class);
            if(snapshot==null || snapshot.content()==null || snapshot.integrity()==null) throw new Failure(409,"SNAPSHOT_CORRUPT");
            if(snapshot.content().version()!=1 || snapshot.integrity().version()!=1) throw new Failure(409,"SNAPSHOT_VERSION_UNSUPPORTED");
            if(!Objects.equals(id,snapshot.content().id()) || !Objects.equals(createdAt,snapshot.content().createdAt())) throw new Failure(409,"SNAPSHOT_CORRUPT");
            String actual=PlanObservationReceiptStore.sha256(mapperWrite(snapshot.content()));
            if(!Objects.equals(actual,storedHash) || !Objects.equals(actual,snapshot.integrity().payloadSha256())) throw new Failure(409,"SNAPSHOT_CORRUPT");
            validateContent(snapshot.content());
            return snapshot;
        } catch(Failure failure) { throw failure; }
        catch(Exception corrupt) { throw new Failure(409,"SNAPSHOT_CORRUPT"); }
    }

    private static void validateContent(Content c) {
        try {
            PlanObservationReceiptStore.validatePayload(c.observation());
            var p=c.observation(); var s=Objects.requireNonNull(c.source());
            Instant.parse(c.createdAt());
            if(c.id()==null || c.id().isBlank() || !Objects.equals(c.sourceObservationId(),p.id())
                    || !Objects.equals(s.sessionId(),p.sessionId()) || !Objects.equals(s.sessionName(),p.sessionName())
                    || !Objects.equals(s.executionId(),p.executionId()) || !Objects.equals(s.connectionId(),p.connectionId())
                    || !Objects.equals(s.dialect(),p.dialect()) || !Objects.equals(s.submittedSql(),p.submittedSql())
                    || !Objects.equals(s.submittedSqlSha256(),p.submittedSqlSha256()) || !Objects.equals(s.capturedAt(),p.capturedAt())
                    || !Objects.equals(s.requestedMode(),p.requestedMode()) || !Objects.equals(s.serverProduct(),p.planCapture().serverProduct())
                    || !Objects.equals(s.serverVersion(),p.planCapture().serverVersion())) throw new IllegalArgumentException();
            checkText(c.annotation().title(),1,120,true); checkText(c.annotation().hypothesis(),0,4000,false); checkText(c.annotation().observation(),0,4000,false);
            checkSection(c.workspace()); checkSection(c.diagnostics()); checkSection(c.mapping());
            if(c.workspace().value()!=null && !Objects.equals(c.workspace().value().sqlFragment(),p.submittedSql())) throw new IllegalArgumentException();
            if(c.diagnostics().value()!=null) {
                var d=c.diagnostics().value(); var e=Objects.requireNonNull(d.evaluation());
                if(c.workspace().value()==null || e.version()!=1 || !Objects.equals(e.executionId(),p.executionId())
                        || !Set.of("VERIFIED","UNVERIFIED","EXPIRED","MISMATCH","UNAVAILABLE").contains(e.sourceState())) throw new IllegalArgumentException();
                Set<String> ids=new HashSet<>();
                for(var f:d.findings()) if(f==null || f.id()==null || !ids.add(f.id()) || f.title()==null || f.message()==null
                        || f.evidence()==null || f.evidence().stream().anyMatch(Objects::isNull) || f.status()==null || f.severity()==null
                        || !Double.isFinite(f.confidence()) || f.confidence()<0 || f.confidence()>1) throw new IllegalArgumentException();
            }
            if(c.mapping().value()!=null) {
                var m=c.mapping().value();
                if(c.workspace().value()==null || m.version()!=1 || !Objects.equals(m.executionId(),p.executionId())
                        || !Objects.equals(m.dialect(),p.dialect()) || !Objects.equals(m.submittedSqlSha256(),p.submittedSqlSha256())
                        || !Objects.equals(m.captureKey(),p.executionId()+":"+p.dialect()+":"+p.submittedSqlSha256())) throw new IllegalArgumentException();
                for(var edge:m.edges()) if(edge==null || edge.targetKind()==null || edge.confidence()==null || edge.relation()==null || edge.reason()==null) throw new IllegalArgumentException();
            }
        } catch(PlanObservationReceiptStore.Failure failure) { throw new Failure(409,failure.code); }
        catch(Exception invalid) { throw new Failure(409,"SNAPSHOT_CORRUPT"); }
    }

    private static void checkSection(Section<?> section) {
        if(section==null || !Set.of("AVAILABLE","DEGRADED","NOT_AVAILABLE").contains(section.state())
                || ("NOT_AVAILABLE".equals(section.state()) ? section.value()!=null || section.reason()==null || section.reason().isBlank() : section.value()==null))
            throw new IllegalArgumentException();
    }

    public void protect(String id, boolean protectedValue) {
        tx.executeWithoutResult(status -> {
            jdbc.update("UPDATE storage_generation SET revision=revision WHERE id=1");
            if (jdbc.queryForObject("SELECT count(*) FROM investigation_snapshots WHERE id=?", Integer.class, id) == 0)
                throw new Failure(404, "SNAPSHOT_UNAVAILABLE");
            jdbc.update("UPDATE storage_generation SET revision=revision+1 WHERE id=1");
            if (protectedValue) jdbc.update("INSERT OR IGNORE INTO investigation_snapshot_protection(snapshot_id,protected_at) VALUES(?,?)", id, clock.instant().toString());
            else jdbc.update("DELETE FROM investigation_snapshot_protection WHERE snapshot_id=?", id);
        });
    }

    public void delete(String id) {
        tx.executeWithoutResult(status -> {
            jdbc.update("UPDATE storage_generation SET revision=revision WHERE id=1");
            jdbc.update("UPDATE storage_generation SET revision=revision+1 WHERE id=1");
            jdbc.update("DELETE FROM investigation_snapshot_protection WHERE snapshot_id=?", id);
            jdbc.update("DELETE FROM investigation_snapshots WHERE id=?", id);
        });
    }

    private Summary summary(String id, String createdAt, String json, String hash, int version, long bytes, boolean pinned) {
        try {
            Snapshot snapshot = decode(id,createdAt,json,hash,version,bytes);
            Content c = snapshot.content();
            boolean partial = c.workspace().state().equals("NOT_AVAILABLE") || c.diagnostics().state().equals("NOT_AVAILABLE")
                    || c.mapping().state().equals("NOT_AVAILABLE") || c.workspace().state().equals("DEGRADED")
                    || c.diagnostics().state().equals("DEGRADED") || c.mapping().state().equals("DEGRADED");
            boolean degraded = c.observation().degraded() || "DEGRADED".equals(c.diagnostics().state()) || "DEGRADED".equals(c.mapping().state());
            return new Summary(id, createdAt, c.annotation().title(), c.source().sessionId(), c.source().sessionName(),
                    c.source().dialect(), c.source().serverProduct(), c.source().serverVersion(), c.source().executionId(),
                    partial, degraded, pinned, bytes,"AVAILABLE",null);
        } catch (Failure corrupt) {
            return new Summary(id, createdAt, "Unavailable snapshot", null, null, null, null, null, null,
                    true, true, pinned, bytes,"UNAVAILABLE",corrupt.code);
        }
    }

    private static boolean hasDegradedFinding(List<DiagnosticFinding> findings) {
        return findings != null && findings.stream().anyMatch(f -> f != null && f.degraded());
    }

    private static void validate(CreateRequest r) {
        if (r == null || r.observationId() == null || r.observationId().isBlank() || r.observationId().length() > 100
                || r.requestId() == null || r.requestId().isBlank() || r.requestId().length() > 100 || r.annotation() == null)
            throw new Failure(400, "SNAPSHOT_INVALID_REQUEST");
        if (!r.sensitiveDataConfirmed()) throw new Failure(422, "SNAPSHOT_SENSITIVE_DATA_CONFIRMATION_REQUIRED");
        checkText(r.annotation().title(), 1, 120, true);
        checkText(r.annotation().hypothesis(), 0, 4000, false);
        checkText(r.annotation().observation(), 0, 4000, false);
        if (r.findings() != null && r.findings().stream().anyMatch(Objects::isNull)) throw new Failure(400, "SNAPSHOT_INVALID_REQUEST");
    }

    private static void checkText(String value, int min, int max, boolean required) {
        if (value == null) { if (required) throw new Failure(400, "SNAPSHOT_INVALID_ANNOTATION"); return; }
        if (hasUnpairedSurrogate(value)) throw new Failure(400, "SNAPSHOT_INVALID_ANNOTATION");
        int count = value.codePointCount(0, value.length());
        if ((required && value.strip().isEmpty()) || count < min || count > max) throw new Failure(400, "SNAPSHOT_INVALID_ANNOTATION");
    }

    private static boolean hasUnpairedSurrogate(String value) {
        for (int i=0;i<value.length();i++) {
            char c=value.charAt(i);
            if (Character.isHighSurrogate(c)) { if (i+1>=value.length() || !Character.isLowSurrogate(value.charAt(++i))) return true; }
            else if (Character.isLowSurrogate(c)) return true;
        }
        return false;
    }

    public static class Failure extends RuntimeException {
        public final int status; public final String code;
        public Failure(int status, String code) { super(code); this.status=status; this.code=code; }
    }
}
