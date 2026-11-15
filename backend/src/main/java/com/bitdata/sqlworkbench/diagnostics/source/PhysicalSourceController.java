package com.bitdata.sqlworkbench.diagnostics.source;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/diagnostics")
public class PhysicalSourceController {
    private final PhysicalSourcePlanService service;
    private final ComplexSourcePlanService complex;
    private com.bitdata.sqlworkbench.diagnostics.trace.DiagnosticAdmission admission;
    private SourceObservationReceipts receipts;
    private SqlServerSourcePlanService sqlServer;
    private com.bitdata.sqlworkbench.connection.ConnectionService connections;
    public PhysicalSourceController(PhysicalSourcePlanService service,ComplexSourcePlanService complex) {this.service=service;this.complex=complex;}
    @org.springframework.beans.factory.annotation.Autowired
    void configure(com.bitdata.sqlworkbench.diagnostics.trace.DiagnosticAdmission admission,SourceObservationReceipts receipts,
                   SqlServerSourcePlanService sqlServer,com.bitdata.sqlworkbench.connection.ConnectionService connections) {
        this.admission=admission;this.receipts=receipts;this.sqlServer=sqlServer;this.connections=connections;
    }
    @PostMapping("/physical-source-plan")
    public PhysicalSourcePlanService.Plan inspect(@RequestBody PhysicalSourcePlanService.Request request) {
        if(admission==null)throw com.bitdata.sqlworkbench.diagnostics.trace.DiagnosticAdmission.failure(503,"DIAGNOSTIC_ADMISSION_UNAVAILABLE");
        requireSynchronousDialect(request.connectionId());
        admission.source(request.diagnosticContext(),request.connectionId(),request.sql(),request.source());
        String version=admission.connectionVersion(request.connectionId());
        var plan="sqlserver".equals(connections.getById(request.connectionId()).orElseThrow().dialect())?sqlServer.inspectDirect(request):service.inspect(request);
        admission.revalidate(request.diagnosticContext(),request.connectionId(),version);
        if(plan.sourceSql()==null||!plan.verified())return plan;
        String receipt=receipts.issue(request,plan.sourceSql(),plan.keys(),version);
        return new PhysicalSourcePlanService.Plan(plan.status(),plan.verified(),plan.errorReason(),plan.connectionId(),plan.originalSql(),plan.metadataSql(),plan.schema(),plan.relation(),plan.sourceSql(),plan.keys(),plan.observedAt(),receipt);
    }
    @PostMapping("/physical-sources-plan")
    public ComplexSourcePlanService.Plan inspectSources(@RequestBody PhysicalSourcePlanService.Request request) {
        if(admission==null)throw com.bitdata.sqlworkbench.diagnostics.trace.DiagnosticAdmission.failure(503,"DIAGNOSTIC_ADMISSION_UNAVAILABLE");
        requireSynchronousDialect(request.connectionId());
        admission.source(request.diagnosticContext(),request.connectionId(),request.sql(),request.source());
        String version=admission.connectionVersion(request.connectionId());
        var plan="sqlserver".equals(connections.getById(request.connectionId()).orElseThrow().dialect())?sqlServer.inspect(request,false):complex.inspect(request);
        var sources=plan.sources().stream().map(s->new ComplexSourcePlanService.Source(s.occurrence(),s.schema(),s.relation(),s.path(),s.sourceSql(),s.keys(),s.mappings(),receipts.issue(request,s.sourceSql(),s.keys(),version))).toList();
        admission.revalidate(request.diagnosticContext(),request.connectionId(),version);
        return new ComplexSourcePlanService.Plan(plan.status(),plan.verified(),plan.errorReason(),plan.connectionId(),plan.originalSql(),plan.metadataSql(),sources,plan.issues(),plan.observedAt());
    }
    private void requireSynchronousDialect(String connectionId) {
        if(connections.getById(connectionId).map(c->"oracle".equals(c.dialect())).orElse(false))
            throw com.bitdata.sqlworkbench.diagnostics.trace.DiagnosticAdmission.failure(422,"ORACLE_SOURCE_ASYNC_REQUIRED");
    }
}
