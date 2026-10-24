package com.bitdata.sqlworkbench.history;

import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/history")
public class HistoryController {

    private final HistoryStore historyStore;

    public HistoryController(HistoryStore historyStore) { this.historyStore = historyStore; }

    @GetMapping
    public List<HistoryEntry> list(@RequestParam(defaultValue = "50") int limit) {
        return historyStore.findRecent(limit);
    }

    @GetMapping("/session/{sessionId}")
    public List<HistoryEntry> bySession(@PathVariable String sessionId, @RequestParam(defaultValue = "20") int limit) {
        return historyStore.findBySessionId(sessionId, limit);
    }

    public record AddHistoryRequest(
        String id,
        String sessionId,
        String sqlText,
        Integer durationMs,
        Integer rowCount,
        com.bitdata.sqlworkbench.execution.model.ResultCompleteness resultCompleteness
    ) {
        public AddHistoryRequest(String id, String sessionId, String sqlText, Integer durationMs, Integer rowCount) {
            this(id, sessionId, sqlText, durationMs, rowCount, null);
        }
    }

    @PostMapping
    public HistoryEntry add(@RequestBody AddHistoryRequest req) {
        HistoryEntry entry = new HistoryEntry(
            req.id() != null ? req.id() : java.util.UUID.randomUUID().toString(),
            req.sessionId(),
            req.sqlText(),
            java.time.Instant.now(),
            req.durationMs(),
            req.rowCount(), req.resultCompleteness()
        );
        return historyStore.save(entry);
    }
}
