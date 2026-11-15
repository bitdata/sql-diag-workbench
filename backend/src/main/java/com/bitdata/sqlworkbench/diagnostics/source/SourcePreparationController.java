package com.bitdata.sqlworkbench.diagnostics.source;

import com.bitdata.sqlworkbench.diagnostics.trace.SourcePreparationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/diagnostics/source-preparations")
public final class SourcePreparationController {
    public record Request(String kind, PhysicalSourcePlanService.Request request) {}
    private final SourcePreparationService service;
    public SourcePreparationController(SourcePreparationService service){this.service=service;}
    @PostMapping public ResponseEntity<Map<String,String>> submit(@RequestBody Request request) {
        if(request==null||!java.util.Set.of("DIRECT","COMPLEX").contains(request.kind()==null?"":request.kind()))
            throw com.bitdata.sqlworkbench.diagnostics.trace.DiagnosticAdmission.failure(422,"SOURCE_PREPARATION_INPUT_INVALID");
        return ResponseEntity.accepted().body(Map.of("preparationId",service.submit(new SourcePreparationService.Submit(
            SourcePreparationService.Kind.valueOf(request.kind()),request.request()))));
    }
    @GetMapping("/{id}") public SourcePreparationService.Progress get(@PathVariable String id){return service.get(id);}
    @DeleteMapping("/{id}") public Map<String,Boolean> cancel(@PathVariable String id){return Map.of("cancelled",service.cancel(id));}
}
