package com.bitdata.sqlworkbench.diagnostics;

import com.bitdata.sqlworkbench.diagnostics.trace.*;
import com.bitdata.sqlworkbench.execution.model.ExecutionResult.PlanNode;
import com.bitdata.sqlworkbench.execution.model.ExecutionResult.Timing;
import com.bitdata.sqlworkbench.sql.model.FragmentNode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.bitdata.sqlworkbench.execution.model.ExecutionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.Collections;
import com.bitdata.sqlworkbench.diagnostics.mapping.PlanSqlMapping;
import com.bitdata.sqlworkbench.diagnostics.mapping.PlanSqlMappingService;
import com.bitdata.sqlworkbench.connection.DatabaseStrategyFactory;
import com.bitdata.sqlworkbench.sql.SqlDialectResolver;
import com.bitdata.sqlworkbench.sql.SqlPositionService;

/**
 * REST controller for diagnostic analysis.
 * POST /api/diagnostics/analyze — run all rules against a fragment
 * POST /api/diagnostics/trace-async — submit async expected row trace
 * GET  /api/diagnostics/trace-async/{traceId} — poll trace progress
 * DELETE /api/diagnostics/trace-async/{traceId} — cancel trace
 * GET  /api/diagnostics/reports — retrieve persisted reports for a session
 */
@RestController
@RequestMapping("/api/diagnostics")
public class DiagnosticsController {

    private static final Logger log = LoggerFactory.getLogger(DiagnosticsController.class);

    private final RuleRegistry ruleRegistry;
    private final RowTraceEngine traceEngine;
    private final TraceStore traceStore;
    private final DiagnosticReportStore reportStore;
    private final ObjectMapper objectMapper;
    private final FindingVerifier findingVerifier;
    private final PlanSqlMappingService planSqlMappingService;
    @org.springframework.beans.factory.annotation.Autowired
    private com.bitdata.sqlworkbench.execution.ExecutionEngine executionEngine;

    @org.springframework.beans.factory.annotation.Autowired
    public DiagnosticsController(
            RuleRegistry ruleRegistry,
            RowTraceEngine traceEngine,
            TraceStore traceStore,
            DiagnosticReportStore reportStore,
            ObjectMapper objectMapper,
            FindingVerifier findingVerifier,
            PlanSqlMappingService planSqlMappingService) {
        this.ruleRegistry = ruleRegistry;
        this.traceEngine = traceEngine;
        this.traceStore = traceStore;
        this.reportStore = reportStore;
        this.objectMapper = objectMapper;
        this.findingVerifier = findingVerifier;
        this.planSqlMappingService = planSqlMappingService;
    }

    /** Backward-compatible constructor for focused controller tests and embedders. */
    public DiagnosticsController(
            RuleRegistry ruleRegistry,
            RowTraceEngine traceEngine,
            TraceStore traceStore,
            DiagnosticReportStore reportStore,
            ObjectMapper objectMapper,
            FindingVerifier findingVerifier) {
        this(ruleRegistry, traceEngine, traceStore, reportStore, objectMapper, findingVerifier,
            new PlanSqlMappingService(new SqlPositionService(
                new SqlDialectResolver(DatabaseStrategyFactory.withBuiltinStrategies()))));
    }

    public record AnalyzeRequest(
        FragmentNode fragment,
        PlanNode plan,
        Timing timing,
        String sessionId,
        String fragmentId,
        String executionId,
        String connectionId
    ) {
        public AnalyzeRequest(FragmentNode fragment,PlanNode plan,Timing timing,String sessionId,String fragmentId) {
            this(fragment,plan,timing,sessionId,fragmentId,null,null);
        }
    }

    public record AnalyzeResponse(
        List<DiagnosticFinding> findings, ExecutionResult.Persistence reportPersistence, Evaluation evaluation
    ) {
        public AnalyzeResponse(List<DiagnosticFinding> findings) { this(findings,null,null); }
        public AnalyzeResponse(List<DiagnosticFinding> findings,ExecutionResult.Persistence persistence) { this(findings,persistence,null); }
    }

    @PostMapping("/analyze")
    public AnalyzeResponse analyze(@RequestBody AnalyzeRequest request) {
        if(request.fragment()==null || request.fragment().sqlFragment()==null ||
                (request.fragmentId()!=null && !request.fragmentId().equals(request.fragment().id())))
            throw new IllegalArgumentException("fragment and matching fragmentId are required");
        var observation=executionEngine==null
            ?new com.bitdata.sqlworkbench.execution.ExecutionEngine.PlanObservation("UNVERIFIED","UNVERIFIED_PLAN_SOURCE",null)
            :executionEngine.resolvePlanObservation(request.executionId(),request.fragment().sqlFragment(),request.connectionId(),request.sessionId());
        var result=ruleRegistry.evaluate(request.fragment(),observation,request.executionId());
        return new AnalyzeResponse(result.findings(),persistReport(request,result.findings(),result.evaluation()),result.evaluation());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    // Original-text observation uses the same retained server receipt, without a DAG node.
    public ResponseEntity<?> invalidAnalysis(IllegalArgumentException failure) {
        return ResponseEntity.badRequest().body(java.util.Map.of("code","VALIDATION_ERROR","errorReason","VALIDATION_ERROR",
            "degraded",true,"message","Invalid fragment or diagnostic identity"));
    }

    public record PlanRequest(String executionId,String sql,String connectionId,String sessionId) {}

    @PostMapping("/plan")
    public AnalyzeResponse analyzePlan(@RequestBody PlanRequest request) {
        if(request.executionId()==null||request.executionId().isBlank()||request.sql()==null||request.sql().isBlank()||request.connectionId()==null||request.connectionId().isBlank())
            throw new IllegalArgumentException("Plan execution, exact SQL and connection are required");
        var observation=executionEngine.resolvePlanObservation(request.executionId(),request.sql(),request.connectionId(),request.sessionId());
        var subject=new FragmentNode("plan-execution:"+request.executionId(),com.bitdata.sqlworkbench.sql.model.FragmentType.ORIGINAL,"Submitted SQL",request.sql(),true,null,0,null,List.of());
        var evaluated=ruleRegistry.evaluate(subject,observation,request.executionId());
        var findings=evaluated.findings().stream().map(DiagnosticFinding::withoutFragment).toList();
        var persistence=new AnalyzeRequest(subject,null,null,request.sessionId(),null,request.executionId(),request.connectionId());
        return new AnalyzeResponse(findings,persistReport(persistence,findings,evaluated.evaluation()),evaluated.evaluation());
    }

    public record MappingRequest(String executionId, String sql, String dialect, String connectionId, String sessionId,
                                 FragmentNode fragment, List<DiagnosticFinding> findings) {}

    /** Return an execution-scoped, additive plan → SQL/fragment/evidence mapping. */
    @PostMapping("/plan-mapping")
    public PlanSqlMapping planMapping(@RequestBody MappingRequest request) {
        if (request == null || request.executionId() == null || request.executionId().isBlank()
            || request.sql() == null || request.sql().isBlank()
            || request.connectionId() == null || request.connectionId().isBlank()
            || !java.util.Set.of("postgresql", "mysql").contains(request.dialect() == null ? "" : request.dialect())) {
            throw new IllegalArgumentException("executionId, exact SQL and connection are required");
        }
        var observation = executionEngine.resolvePlanObservation(
            request.executionId(), request.sql(), request.connectionId(), request.sessionId());
        String dialect = request.dialect();
        if (!"VERIFIED".equals(observation.sourceState()) || observation.result() == null) {
            String reason = observation.errorReason() == null ? "PLAN_SOURCE_UNAVAILABLE" : observation.errorReason();
            var unavailable = planSqlMappingService.map(request.executionId(), dialect, request.sql(), null,
                request.fragment(), request.findings() == null ? Collections.emptyList() : request.findings());
            return new PlanSqlMapping(unavailable.version(), unavailable.executionId(), unavailable.captureKey(),
                unavailable.dialect(), unavailable.submittedSqlSha256(), unavailable.edges(), true, reason,
                List.of(new PlanSqlMapping.MappingIssue(reason, null, "retained plan observation is " + observation.sourceState())));
        }
        return planSqlMappingService.map(request.executionId(), dialect, request.sql(), observation.result(),
            request.fragment(), request.findings() == null ? Collections.emptyList() : request.findings());
    }


    @PostMapping("/trace-async")
    public ResponseEntity<?> submitTrace(@RequestBody TraceRequest request) {
        try {
            String traceId = traceEngine.submit(request);
            return ResponseEntity.ok(java.util.Map.of("traceId", traceId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(java.util.Map.of(
                "code", "TRACE_VALIDATION_ERROR", "errorReason", "TRACE_VALIDATION_ERROR", "degraded", true,
                "message", e.getMessage()
            ));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(java.util.Map.of(
                "code", "TRACE_ERROR",
                "message", e.getMessage()
            ));
        }
    }

    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<?> malformedRequest(org.springframework.http.converter.HttpMessageNotReadableException failure,
            jakarta.servlet.http.HttpServletRequest request) {
        String code = request.getRequestURI().endsWith("/trace-async") ? "TRACE_VALIDATION_ERROR" : "VALIDATION_ERROR";
        return ResponseEntity.badRequest().body(java.util.Map.of("code", code, "errorReason", code,
            "degraded", true, "message", "Invalid request JSON or field value"));
    }

    @GetMapping("/trace-async/{traceId}")
    public ResponseEntity<?> getTraceProgress(@PathVariable String traceId) {
        TraceProgress progress = traceStore.toProgress(traceId);
        if (progress == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(progress);
    }

    @DeleteMapping("/trace-async/{traceId}")
    public ResponseEntity<?> cancelTrace(@PathVariable String traceId) {
        boolean cancelled = traceEngine.cancel(traceId);
        if (cancelled) {
            return ResponseEntity.ok(java.util.Map.of("status", "cancelled"));
        }
        return ResponseEntity.notFound().build();
    }

    @GetMapping("/reports")
    public List<DiagnosticReport> getReports(@RequestParam String sessionId) {
        return reportStore.findBySessionId(sessionId);
    }

    public record VerifyRequest(
        String findingId,
        String verificationSql,
        String connectionId
    ) {}

    public record VerifyResponse(
        String findingId,
        FindingStatus status,
        String resultSummary,
        String errorReason,
        boolean degraded
    ) {}

    @PostMapping("/verify")
    public ResponseEntity<?> verify(@RequestBody VerifyRequest request) {
        if (request.verificationSql() == null || request.verificationSql().isBlank()) {
            return ResponseEntity.badRequest().body(
                java.util.Map.of("code", "VALIDATION_ERROR", "message", "verificationSql is required"));
        }
        if (request.connectionId() == null || request.connectionId().isBlank()) {
            return ResponseEntity.badRequest().body(
                java.util.Map.of("code", "VALIDATION_ERROR", "message", "connectionId is required"));
        }

        var result = findingVerifier.verify(request.verificationSql(), request.connectionId());
        return ResponseEntity.ok(
            new VerifyResponse(request.findingId(), result.status(), result.summary(),result.errorReason(),result.degraded()));
    }

    private ExecutionResult.Persistence persistReport(AnalyzeRequest request, List<DiagnosticFinding> findings, Evaluation evaluation) {
        if (request.sessionId() == null || (request.fragmentId() == null && request.executionId() == null)) {
            return new ExecutionResult.Persistence("not_requested",null);
        }
        try {
            var report = new DiagnosticReport(
                UUID.randomUUID().toString(),
                request.sessionId(),
                request.fragmentId(),
                objectMapper.writeValueAsString(findings),
                Instant.now(), objectMapper.writeValueAsString(evaluation)
            );
            reportStore.save(report);
            return new ExecutionResult.Persistence("saved",null);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize findings for session={}, fragment={}",
                      request.sessionId(), request.fragmentId(), e);
        } catch (Exception e) {
            log.warn("Failed to persist diagnostic report for session={}, fragment={}",
                     request.sessionId(), request.fragmentId(), e);
        }
        return new ExecutionResult.Persistence("failed","REPORT_SAVE_FAILED");
    }
}
