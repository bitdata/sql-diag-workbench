package com.bitdata.sqlworkbench.execution;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
public class InvestigationSnapshotController {
    private final PlanObservationReceiptStore receipts;
    private final InvestigationSnapshotStore snapshots;
    private final ExecutionEngine engine;

    public InvestigationSnapshotController(PlanObservationReceiptStore receipts,
            InvestigationSnapshotStore snapshots, ExecutionEngine engine) {
        this.receipts = receipts; this.snapshots = snapshots; this.engine = engine;
    }

    public record RetryRequest(String sql, String connectionId, String sessionId) {}
    public record ProtectionRequest(Boolean protectedValue) {}

    @GetMapping("/api/investigation-observation-receipts")
    public PlanObservationReceiptStore.Page receipts(@RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int limit) { return receipts.page(cursor, limit); }

    @GetMapping("/api/investigation-observation-receipts/{id}")
    public PlanObservationReceiptStore.Payload receipt(@PathVariable String id) { return receipts.get(id); }

    @DeleteMapping("/api/investigation-observation-receipts/{id}")
    public ResponseEntity<Void> discardReceipt(@PathVariable String id) {
        receipts.discard(id); return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/investigation-observation-receipts/{executionId}/retry")
    public Object retryReceipt(@PathVariable String executionId, @RequestBody RetryRequest request) {
        if (request == null || request.sql() == null || request.connectionId() == null)
            throw new PlanObservationReceiptStore.Failure(400, "SNAPSHOT_INVALID_REQUEST");
        return engine.retryObservationReceipt(executionId, request.sql(), request.connectionId(), request.sessionId());
    }

    @PostMapping("/api/investigation-snapshots")
    public InvestigationSnapshotStore.CreateResult create(@RequestBody InvestigationSnapshotStore.CreateRequest request) {
        return snapshots.create(request);
    }

    @GetMapping("/api/investigation-snapshots")
    public InvestigationSnapshotStore.Page snapshots(@RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int limit) { return snapshots.page(cursor, limit); }

    @GetMapping("/api/investigation-snapshots/{id}")
    public InvestigationSnapshotStore.Snapshot snapshot(@PathVariable String id) { return snapshots.get(id); }

    @PutMapping("/api/investigation-snapshots/{id}/protection")
    public Map<String,Object> protect(@PathVariable String id, @RequestBody ProtectionRequest request) {
        if (request == null || request.protectedValue() == null) throw new InvestigationSnapshotStore.Failure(400, "SNAPSHOT_INVALID_REQUEST");
        snapshots.protect(id, request.protectedValue()); return Map.of("protected", request.protectedValue());
    }

    @DeleteMapping("/api/investigation-snapshots/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        snapshots.delete(id); return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(PlanObservationReceiptStore.Failure.class)
    public ResponseEntity<Map<String,Object>> receiptFailure(PlanObservationReceiptStore.Failure failure) {
        return ResponseEntity.status(failure.status).body(Map.of("code", failure.code,
                "errorReason", failure.code, "degraded", true));
    }
    @ExceptionHandler({org.springframework.http.converter.HttpMessageNotReadableException.class, org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Map<String,Object>> malformed(Exception failure) {
        return ResponseEntity.badRequest().body(Map.of("code","SNAPSHOT_INVALID_REQUEST","errorReason","SNAPSHOT_INVALID_REQUEST","degraded",true));
    }

    @ExceptionHandler(InvestigationSnapshotStore.Failure.class)
    public ResponseEntity<Map<String,Object>> snapshotFailure(InvestigationSnapshotStore.Failure failure) {
        return ResponseEntity.status(failure.status).body(Map.of("code", failure.code,
                "errorReason", failure.code, "degraded", true));
    }
}
