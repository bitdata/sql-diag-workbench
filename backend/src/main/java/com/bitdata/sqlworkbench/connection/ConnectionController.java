package com.bitdata.sqlworkbench.connection;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/connections")
public class ConnectionController {

    private static final Logger log = LoggerFactory.getLogger(ConnectionController.class);
    private final ConnectionService service;
    private final KeychainStore keychain;

    public ConnectionController(ConnectionService service, KeychainStore keychain) {
        this.service = service;
        this.keychain = keychain;
    }

    @org.springframework.beans.factory.annotation.Autowired
    private com.bitdata.sqlworkbench.execution.PlanCapabilityService planCapabilities;

    @GetMapping("/{id}/plan-capabilities")
    public ResponseEntity<?> planCapabilities(@PathVariable String id) {
        if(service.getById(id).isEmpty()) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(planCapabilities.get(id));
    }

    @GetMapping("/credentials/migration")
    public KeychainStore.Migration migration() { return keychain.migrate(); }

    @PostMapping("/credentials/migration/retry")
    public KeychainStore.Migration retryMigration() { return keychain.migrate(); }

    public record CleanupRequest(String sourceHash,boolean confirmDiscard) {}
    @PostMapping("/credentials/migration/cleanup")
    public KeychainStore.Migration cleanup(@RequestBody CleanupRequest request) {
        return keychain.cleanup(request.sourceHash(),request.confirmDiscard());
    }

    @GetMapping
    public List<ConnectionConfig> listAll() {
        return service.listAll();
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getById(@PathVariable String id) {
        Optional<ConnectionConfig> config = service.getById(id);
        return config.<ResponseEntity<?>>map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody ConnectionRequest request) {
        return ResponseEntity.ok(service.create(request));
    }

    @ExceptionHandler(CredentialFailure.class)
    public ResponseEntity<?> credentialFailure(CredentialFailure failure) {
        return ResponseEntity.status(failure.status).body(failure.envelope());
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<?> unconfirmedSave(IllegalStateException error) {
        return ResponseEntity.internalServerError().body(Map.of("code","STORAGE_ERROR","errorReason","STORAGE_ERROR",
            "message","The connection operation could not be confirmed. Retry the operation.","degraded",true));
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable String id, @RequestBody ConnectionRequest request) {
        log.info("PUT /api/connections/{} — name={}, host={}, port={}", id, request.name(), request.host(), request.port());
        Optional<ConnectionConfig> updated = service.update(id, request);
        if (updated.isPresent()) {
            return ResponseEntity.ok(updated.get());
        }
        log.warn("PUT /api/connections/{}: not found", id);
        return ResponseEntity.notFound().build();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable String id) {
        boolean deleted = service.delete(id);
        if (deleted) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.notFound().build();
    }

    @PostMapping("/test")
    public ResponseEntity<?> test(@RequestBody ConnectionRequest request) {
        try {
            Map<String, Object> result = service.test(request);
            return ResponseEntity.ok(result);
        } catch (CredentialFailure e) {
            return ResponseEntity.ok(Map.of("success",false,"error",e.getMessage(),"errorReason",e.code,"degraded",true));
        } catch (Exception e) {
            log.warn("Connection test could not be confirmed ({})",e.getClass().getSimpleName());
            // Rule #1 same-class sweep: this is the second {"success":false,"error":…} site in
            // backend/src/main — it carries the same classification field as the failure
            // branch of ConnectionService.test (spec §5.1). The null guard is the same one
            // ConnectionService.errorText applies: Map.of rejects null values, so an
            // exception with no message would otherwise surface as a 500 instead of a result.
            return ResponseEntity.ok(Map.of(
                "success", false,
                "error", "The connection test could not be confirmed. Retry the test.",
                "errorReason", "other", "degraded", true
            ));
        }
    }
}
