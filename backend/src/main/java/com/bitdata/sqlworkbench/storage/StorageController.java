package com.bitdata.sqlworkbench.storage;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/storage")
public class StorageController {
    private final StorageService storage;
    public StorageController(StorageService storage) { this.storage=storage; }
    private long integer(JsonNode body,String field) {
        if(!body.path(field).isIntegralNumber() || !body.path(field).canConvertToLong()) throw new StorageService.Failure(400,"INVALID_REQUEST","Missing or invalid integer: "+field);
        return body.path(field).longValue();
    }
    private List<String> ids(JsonNode body) {
        if(!body.path("sessionIds").isArray()) throw new StorageService.Failure(400,"INVALID_REQUEST","sessionIds must be an array.");
        List<String> values=new ArrayList<>();
        for(JsonNode id:body.path("sessionIds")) { if(!id.isTextual()) throw new StorageService.Failure(400,"INVALID_REQUEST","Invalid session ID."); values.add(id.textValue()); }
        return values;
    }
    @GetMapping("/policy") public StorageService.Policy policy() { return storage.policy(); }
    @PutMapping("/policy") public StorageService.Policy apply(@RequestBody JsonNode body) {
        long days=integer(body,"retentionDays"), mib=integer(body,"maxStorageMiB");
        if(!body.path("enabled").isBoolean() || days<1 || days>365 || mib<16 || mib>102400) throw new StorageService.Failure(400,"INVALID_POLICY","Invalid retention policy.");
        return storage.apply(body.path("enabled").booleanValue(),(int)days,(int)mib,integer(body,"expectedRevision"));
    }
    @PutMapping("/protection/{profile}") public Map<String,Object> protect(@PathVariable String profile,@RequestBody JsonNode body) {
        storage.protect(profile,ids(body)); return Map.of("confirmed",true);
    }
    @GetMapping("/preview") public StorageService.Preview preview() { return storage.preview(); }
    @PostMapping("/reclaimable") public Map<String,Object> reclaim(@RequestBody JsonNode body) {
        storage.reclaimable(ids(body),integer(body,"revision")); return Map.of("confirmed",true);
    }
    @GetMapping("/status") public Map<String,Object> status() { return storage.status(); }
    @PostMapping("/cleanup") public ResponseEntity<StorageService.Job> cleanup(@RequestBody JsonNode body) {
        return ResponseEntity.accepted().body(storage.start(integer(body,"expectedPolicyRevision"),"manual"));
    }
    @GetMapping("/jobs/{id}") public StorageService.Job job(@PathVariable String id) { return storage.get(id); }
    @DeleteMapping("/jobs/{id}") public StorageService.Job cancel(@PathVariable String id) { return storage.cancel(id); }
    @ExceptionHandler(StorageService.Failure.class) public ResponseEntity<Map<String,Object>> failure(StorageService.Failure error) {
        return ResponseEntity.status(error.status).body(Map.of("code",error.code,"message",error.getMessage(),"degraded",true));
    }
}
