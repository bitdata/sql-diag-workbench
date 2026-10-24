package com.bitdata.sqlworkbench.session;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
public class SessionEditLinkController {
    private final SessionStore store;
    public SessionEditLinkController(SessionStore store) { this.store = store; }
    @GetMapping("/api/session-edit-links")
    public List<SessionEditLink> links(@RequestParam(defaultValue="") List<String> sessionIds) {
        return store.editLinks(sessionIds.stream().filter(s -> !s.isBlank()).distinct().toList());
    }
}
