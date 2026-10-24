package com.bitdata.sqlworkbench.diagnostics.source;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/diagnostics")
public class PhysicalSourceController {
    private final PhysicalSourcePlanService service;
    private final ComplexSourcePlanService complex;
    public PhysicalSourceController(PhysicalSourcePlanService service,ComplexSourcePlanService complex) {this.service=service;this.complex=complex;}
    @PostMapping("/physical-source-plan")
    public PhysicalSourcePlanService.Plan inspect(@RequestBody PhysicalSourcePlanService.Request request) {
        return service.inspect(request);
    }
    @PostMapping("/physical-sources-plan")
    public ComplexSourcePlanService.Plan inspectSources(@RequestBody PhysicalSourcePlanService.Request request) {
        return complex.inspect(request);
    }
}
