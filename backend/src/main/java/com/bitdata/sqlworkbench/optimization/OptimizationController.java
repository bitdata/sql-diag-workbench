package com.bitdata.sqlworkbench.optimization;

import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import static com.bitdata.sqlworkbench.optimization.OptimizationSql.*;

@RestController
@RequestMapping("/api/optimization")
public class OptimizationController {
    private final OptimizationSources sources;
    private final OptimizationTasks tasks;
    private final OptimizationReportStore reports;
    private final PerformanceExperimentTasks experiments;
    private final PerformanceExperimentReportStore experimentReports;
    private final ObjectMapper json;
    @org.springframework.beans.factory.annotation.Autowired
    public OptimizationController(OptimizationSources sources,OptimizationTasks tasks,OptimizationReportStore reports,PerformanceExperimentTasks experiments,PerformanceExperimentReportStore experimentReports,ObjectMapper json) {
        this.sources=sources;this.tasks=tasks;this.reports=reports;this.experiments=experiments;this.experimentReports=experimentReports;this.json=json;
    }
    /** Compatibility seam for existing controller tests; Spring uses the full constructor. */
    public OptimizationController(OptimizationSources sources,OptimizationTasks tasks,OptimizationReportStore reports,ObjectMapper json) {
        this(sources,tasks,reports,null,null,json);
    }
    private <T> T request(jakarta.servlet.http.HttpServletRequest request,Class<T> type) {
        if(request.getContentLengthLong()>128*1024)throw OptimizationFailure.invalid("REQUEST_BUDGET_EXCEEDED");
        try {
            byte[] bytes=request.getInputStream().readNBytes(128*1024+1);
            if(bytes.length>128*1024)throw OptimizationFailure.invalid("REQUEST_BUDGET_EXCEEDED");
            return json.readValue(bytes,type);
        }catch(OptimizationFailure e){throw e;}catch(Exception e){throw OptimizationFailure.invalid("INVALID_REQUEST");}
    }
    @PostMapping("/candidates") public Generation candidates(jakarta.servlet.http.HttpServletRequest request) {return sources.generate(request(request,CandidateRequest.class));}
    @PostMapping("/validations") public Submission submit(jakarta.servlet.http.HttpServletRequest request) {return tasks.submit(request(request,ValidationRequest.class));}
    @GetMapping("/validations/{id}") public Report get(@PathVariable String id) {return tasks.get(id);}
    @DeleteMapping("/validations/{id}") public Report cancel(@PathVariable String id) {return tasks.cancel(id);}
    @PostMapping("/experiments") public PerformanceExperimentContract.Submission submitExperiment(jakarta.servlet.http.HttpServletRequest request) {return experiments.submit(request(request,PerformanceExperimentContract.Request.class));}
    @GetMapping("/experiments/{id}") public PerformanceExperimentContract.Report getExperiment(@PathVariable String id) {return experiments.get(id);}
    @DeleteMapping("/experiments/{id}") public PerformanceExperimentContract.Report cancelExperiment(@PathVariable String id) {return experiments.cancel(id);}
    @GetMapping("/experiment-reports") public PerformanceExperimentContract.Page experimentPage(@RequestParam String sessionId,@RequestParam(required=false) String cursor) {return experimentReports.page(sessionId,cursor);}
    @GetMapping("/experiment-reports/{id}") public Object experimentReport(@PathVariable String id) {return experimentReports.find(id);}
    @GetMapping("/reports") public ReportPage page(@RequestParam String sessionId,@RequestParam(required=false) String cursor) {return reports.page(sessionId,cursor);}
    @GetMapping("/reports/{id}") public Object report(@PathVariable String id) {return reports.find(id);}
    @ExceptionHandler(OptimizationFailure.class) public ResponseEntity<?> failure(OptimizationFailure e) {return ResponseEntity.status(e.status).body(Map.of("code",e.code,"message",e.reason,"errorReason",e.reason,"degraded",true));}
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class) public ResponseEntity<?> malformed() {return failure(OptimizationFailure.invalid("INVALID_REQUEST"));}
}
