package com.bitdata.sqlworkbench.execution;

import com.bitdata.sqlworkbench.execution.model.ExecutionRequest;
import com.bitdata.sqlworkbench.execution.model.ExecutionResult;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/execution")
public class ExecutionController {

    private static final Logger log = LoggerFactory.getLogger(ExecutionController.class);
    private final ExecutionEngine engine;

    public ExecutionController(ExecutionEngine engine) {
        this.engine = engine;
    }

    @PostMapping("/execute")
    public ResponseEntity<?> execute(@RequestBody ExecutionRequest request) {
        if (request.sql() == null || request.sql().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                "code", "VALIDATION_ERROR",
                "message", "SQL text is required"
            ));
        }
        if (request.connectionId() == null || request.connectionId().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                "code", "VALIDATION_ERROR",
                "message", "connectionId is required"
            ));
        }

        try {
            String executionId = engine.execute(
                request.sql(), request.connectionId(),
                request.queryTimeoutSeconds(), request.maxRows(), request.sessionId());
            return ResponseEntity.ok(Map.of("executionId", executionId));
        } catch (ExecutionCapacityException e) {
            return ResponseEntity.status(429).body(Map.of("code", "EXECUTION_CAPACITY_EXHAUSTED", "message", e.getMessage()));
        } catch (Exception e) {
            log.error("Failed to submit execution", e);
            return ResponseEntity.internalServerError().body(Map.of(
                "code", "EXECUTION_ERROR",
                "message", e.getMessage()
            ));
        }
    }

    /**
     * Performance-mode path: run EXPLAIN ANALYZE on the SQL and return the
     * parsed plan tree. Does not return rows. See {@code /execute} for the
     * Data-mode counterpart.
     */
    @PostMapping("/analyze")
    public ResponseEntity<?> analyze(@RequestBody ExecutionRequest request) {
        if (request.analyzeMode()!=null && !java.util.Set.of("estimated","actual").contains(request.analyzeMode())) {
            return invalidMode();
        }
        if (request.sql() == null || request.sql().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                "code", "VALIDATION_ERROR",
                "message", "SQL text is required"
            ));
        }
        if (request.connectionId() == null || request.connectionId().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                "code", "VALIDATION_ERROR",
                "message", "connectionId is required"
            ));
        }

        try {
            String executionId = engine.analyze(
                request.sql(), request.connectionId(),
                request.queryTimeoutSeconds(), request.maxRows(),
                request.analyzeMode(), request.sessionId());
            return ResponseEntity.ok(Map.of("executionId", executionId));
        } catch (ExecutionCapacityException e) {
            return ResponseEntity.status(429).body(Map.of("code", "EXECUTION_CAPACITY_EXHAUSTED", "message", e.getMessage()));
        } catch (Exception e) {
            log.error("Failed to submit analyze", e);
            return ResponseEntity.internalServerError().body(Map.of(
                "code", "EXECUTION_ERROR",
                "message", e.getMessage()
            ));
        }
    }

    private ResponseEntity<?> invalidMode() {
        return ResponseEntity.badRequest().body(Map.of("code","ANALYZE_VALIDATION_ERROR","errorReason","INVALID_ANALYZE_MODE",
            "degraded",true,"message","analyzeMode must be estimated or actual"));
    }
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<?> malformed(org.springframework.http.converter.HttpMessageNotReadableException e,
            jakarta.servlet.http.HttpServletRequest request) {
        if(request.getRequestURI().endsWith("/analyze")) return invalidMode();
        return ResponseEntity.badRequest().body(Map.of("code","VALIDATION_ERROR","message","Invalid request JSON","degraded",true));
    }

    @GetMapping("/execute/{executionId}")
    public ResponseEntity<?> getStatus(@PathVariable String executionId) {
        ExecutionResult result = engine.getStatus(executionId);
        if (result == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(result);
    }

    @DeleteMapping("/execute/{executionId}")
    public ResponseEntity<?> cancel(@PathVariable String executionId) {
        boolean cancelled = engine.cancel(executionId);
        if (cancelled) {
            return ResponseEntity.ok(Map.of("status", "cancelled"));
        }
        return ResponseEntity.notFound().build();
    }
}
