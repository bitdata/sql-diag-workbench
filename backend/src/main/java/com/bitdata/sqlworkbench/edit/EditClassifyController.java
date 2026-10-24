package com.bitdata.sqlworkbench.edit;

import com.bitdata.sqlworkbench.audit.EditAuditStore;
import com.bitdata.sqlworkbench.audit.EditAuditStore.Action;
import com.bitdata.sqlworkbench.session.Session;
import com.bitdata.sqlworkbench.session.SessionStore;
import com.bitdata.sqlworkbench.sql.diff.SqlCanonicalizer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.calcite.sql.parser.SqlParseException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Spec §3.2 / §8.3 — ADR-0002 editor operation classifier + audit log writer.
 *
 * <p>Frontend sends a (before, after) pair from the editor's save action.
 * Backend answers with either:
 * <ul>
 *   <li><b>NAVIGATE</b>: the canonical SQL of {@code after} matches an
 *       existing fragment in the session tree. The edit should NOT create
 *       a new node — the user is being collapsed to an existing fragment.
 *       The {@code equivalentNodeId} is returned so the frontend can
 *       select the existing node.</li>
 *   <li><b>EDIT</b>: no match. Frontend proceeds to create a new
 *       USER_EDITED fragment.</li>
 * </ul>
 *
 * <p>Every classification decision is recorded in {@code edit_audit} so
 * the user's actual keystrokes (including the ones that got "swallowed"
 * by the equivalence collapse) survive.
 *
 * @deprecated Use {@link com.bitdata.sqlworkbench.fragment.FragmentController}
 *     POST /api/fragment/create-user-edit instead, which integrates classification
 *     with node creation (ADR-0002).
 */
@Deprecated
@RestController
@RequestMapping("/api/edit")
public class EditClassifyController {

    private final SessionStore sessions;
    private final EditAuditStore audit;
    private final ObjectMapper mapper;

    public EditClassifyController(SessionStore sessions, EditAuditStore audit) {
        this.sessions = sessions;
        this.audit = audit;
        this.mapper = new ObjectMapper();
    }

    public record ClassifyRequest(
        String sessionId,
        String before,
        String after,
        String dialect
    ) {}

    public record ClassifyResponse(
        String action,                 // "NAVIGATE" or "EDIT"
        String equivalentNodeId,       // non-null only for NAVIGATE
        String canonicalSql,           // the canonical form used for matching
        String auditId                 // id of the audit row written
    ) {}

    @PostMapping("/classify")
    public ResponseEntity<?> classify(@RequestBody ClassifyRequest request) {
        if (request == null
            || request.sessionId() == null || request.sessionId().isBlank()
            || request.before() == null || request.before().isBlank()
            || request.after()  == null || request.after().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                "code", "VALIDATION_ERROR",
                "message", "sessionId, before, after are required"
            ));
        }

        String dialect = (request.dialect() != null && !request.dialect().isBlank())
            ? request.dialect() : "postgresql";

        // Compute canonical SQL of `after` (P0+P1).
        String canonical;
        try {
            canonical = SqlCanonicalizer.canonicalizeSql(request.after(), dialect);
        } catch (SqlParseException e) {
            return ResponseEntity.unprocessableEntity().body(Map.of(
                "code", "SQL_PARSE_ERROR",
                "message", e.getMessage()
            ));
        }

        // Walk the session's existing tree and find a match.
        Session session = sessions.findById(request.sessionId()).orElse(null);
        String equivalentId = null;
        if (session != null && session.fragmentTreeJson() != null) {
            try {
                JsonNode root = mapper.readTree(session.fragmentTreeJson());
                equivalentId = findEquivalent(root, canonical);
            } catch (Exception e) {
                // Tree parse failure shouldn't block classification.
                equivalentId = null;
            }
        }

        Action action = equivalentId != null ? Action.NAVIGATE : Action.EDIT;
        var entry = audit.record(request.sessionId(), request.before(), request.after(),
            action, equivalentId);

        return ResponseEntity.ok(new ClassifyResponse(
            action.name(),
            equivalentId,
            canonical,
            entry.id()
        ));
    }

    /** Walk the FragmentNode JSON tree, return first node whose canonicalSql
     *  matches {@code target}. Returns null if no match. */
    private String findEquivalent(JsonNode node, String target) {
        if (node == null || node.isNull()) return null;
        if (node.isObject()) {
            JsonNode canon = node.get("canonicalSql");
            if (canon != null && !canon.isNull() && target.equals(canon.asText())) {
                JsonNode id = node.get("id");
                if (id != null) return id.asText();
            }
            JsonNode children = node.get("children");
            if (children != null && children.isArray()) {
                for (JsonNode child : children) {
                    String found = findEquivalent(child, target);
                    if (found != null) return found;
                }
            }
        }
        return null;
    }

    /** Optional read endpoint for the audit log (no UI yet, but useful for
     *  debugging / external tools). */
    @GetMapping("/audit/{sessionId}")
    public ResponseEntity<List<Map<String, Object>>> recentAudit(
            @PathVariable String sessionId,
            @RequestParam(defaultValue = "100") int limit) {
        return ResponseEntity.ok(audit.recentForSession(sessionId, limit));
    }
}
