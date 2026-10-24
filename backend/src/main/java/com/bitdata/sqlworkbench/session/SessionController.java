package com.bitdata.sqlworkbench.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.SQLException;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.sqlite.SQLiteException;
import org.sqlite.SQLiteErrorCode;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RestController
@RequestMapping("/api/sessions")
public class SessionController {
    private static final Logger log = LoggerFactory.getLogger(SessionController.class);
    private final SessionStore sessionStore;
    private final ObjectMapper json;
    public SessionController(SessionStore sessionStore, ObjectMapper json) { this.sessionStore = sessionStore; this.json = json; }
    public record CreateSessionRequest(String id, String name, String sqlText, String dialect,
            String connectionId, String fragmentTreeJson, String workspaceSnapshotJson, SessionEditLink.Origin editOrigin,
            String creationRequestId, String workspaceProfileId) {
        public CreateSessionRequest(String id, String name, String sqlText, String dialect, String connectionId,
                String fragmentTreeJson, String workspaceSnapshotJson, SessionEditLink.Origin editOrigin, String creationRequestId) {
            this(id,name,sqlText,dialect,connectionId,fragmentTreeJson,workspaceSnapshotJson,editOrigin,creationRequestId,null);
        }
        public CreateSessionRequest(String id, String name, String sqlText, String dialect,
                String connectionId, String fragmentTreeJson, String workspaceSnapshotJson, SessionEditLink.Origin editOrigin) {
            this(id, name, sqlText, dialect, connectionId, fragmentTreeJson, workspaceSnapshotJson, editOrigin, null, null);
        }
    }
    public record UpdateSessionRequest(String name, String sqlText, String dialect, String connectionId, String fragmentTreeJson) {}

    @GetMapping public List<Session> list() { return sessionStore.findAll(); }
    @GetMapping("/{id}") public ResponseEntity<Session> get(@PathVariable String id) {
        return sessionStore.findById(id).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }
    @PostMapping public Session create(@RequestBody CreateSessionRequest req) {
        try { return createValidated(req); }
        catch (SessionStore.CreationFailure e) { throw e; }
        catch (ResponseStatusException e) { throw new SessionStore.CreationFailure("CREATION_VALIDATION_ERROR", e.getReason()); }
        catch (DataAccessException | CannotCreateTransactionException e) {
            for (Throwable cause = e; cause != null; cause = cause.getCause()) {
                if (cause instanceof SQLException sql && ((sql.getErrorCode() & 255) == 5 || (sql.getErrorCode() & 255) == 6))
                    throw new SessionStore.CreationFailure("CREATION_STORAGE_BUSY", "Storage is busy. Retry this same operation.");
                // SQLite JDBC exposes extended constraint codes on SQLiteException;
                // Spring can wrap these as UncategorizedSQLException rather than integrity violations.
                if (cause instanceof SQLiteException sqlite && (sqlite.getResultCode() == SQLiteErrorCode.SQLITE_CONSTRAINT_UNIQUE
                        || sqlite.getResultCode() == SQLiteErrorCode.SQLITE_CONSTRAINT_PRIMARYKEY))
                    throw new SessionStore.CreationFailure("CREATION_REQUEST_CONFLICT", "The requested session identity is already in use. Start a new operation.");
            }
            log.error("Session creation could not be confirmed", e);
            throw new SessionStore.CreationFailure("CREATION_INTERNAL_ERROR", "Session creation could not be confirmed. Retry this same operation.");
        }
        catch (RuntimeException e) {
            log.error("Session creation could not be confirmed", e);
            throw new SessionStore.CreationFailure("CREATION_INTERNAL_ERROR", "Session creation could not be confirmed. Retry this same operation.");
        }
    }
    private Session createValidated(CreateSessionRequest req) {
        if (req.workspaceProfileId() != null && !req.workspaceProfileId().matches("[0-9a-fA-F-]{36}")) throw bad("Invalid workspaceProfileId");
        if (req.creationRequestId() != null && !req.creationRequestId().matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
            throw bad("Invalid creationRequestId");
        var origin = req.editOrigin();
        if (origin != null && (blank(origin.sourceSessionId()) || blank(origin.sourceNodeId()) || blank(origin.targetNodeId()) || origin.sourceTabName() == null))
            throw bad("Invalid edit origin");
        String tree = req.workspaceSnapshotJson() == null ? req.fragmentTreeJson() : snapshotTree(req.workspaceSnapshotJson());
        return sessionStore.createWithOrigin(Session.builder()
            .id(req.id() != null ? req.id() : java.util.UUID.randomUUID().toString())
            .name(req.name() != null ? req.name() : "Untitled").sqlText(req.sqlText() != null ? req.sqlText() : "")
            .dialect(req.dialect() != null ? req.dialect() : "postgresql").connectionId(req.connectionId())
            .fragmentTreeJson(tree).workspaceSnapshotJson(req.workspaceSnapshotJson()).build(), origin,
            req.creationRequestId(), req.creationRequestId() == null ? null : creationFingerprint(req), req.workspaceProfileId());
    }
    // v1 is a fixed ordered tuple of effective INITIAL request fields, never live session fields.
    private String creationFingerprint(CreateSessionRequest req) {
        var origin = req.editOrigin();
        var fields = Arrays.asList(req.id(), req.name() == null ? "Untitled" : req.name(),
            req.sqlText() == null ? "" : req.sqlText(), req.dialect() == null ? "postgresql" : req.dialect(),
            req.connectionId(), req.fragmentTreeJson(), req.workspaceSnapshotJson(), origin == null ? null :
                Arrays.asList(origin.sourceSessionId(), origin.sourceNodeId(), origin.sourceTabName(), origin.targetNodeId()));
        try {
            return "v1:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsString(fields).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            log.error("Could not fingerprint session creation", e);
            throw new SessionStore.CreationFailure("CREATION_INTERNAL_ERROR", "Session creation could not be confirmed. Retry this same operation.");
        }
    }
    @ExceptionHandler(SessionStore.CreationFailure.class)
    public ResponseEntity<Map<String,String>> creationFailure(SessionStore.CreationFailure error) {
        int status = switch (error.code) {
            case "CREATION_REQUEST_CONFLICT" -> 409;
            case "CREATION_TARGET_GONE" -> 410;
            case "CREATION_STORAGE_BUSY" -> 503;
            case "CREATION_VALIDATION_ERROR" -> 400;
            default -> 500;
        };
        return ResponseEntity.status(status).body(Map.of("code", error.code, "message", error.getMessage()));
    }
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String,String>> unreadableRequest(HttpMessageNotReadableException error) {
        return ResponseEntity.badRequest().body(Map.of("code", "CREATION_VALIDATION_ERROR", "message", "Invalid session request JSON"));
    }
    @PutMapping("/{id}") public ResponseEntity<Session> update(@PathVariable String id, @RequestBody UpdateSessionRequest req) {
        if (req.sqlText() != null || req.fragmentTreeJson() != null || req.dialect() != null || req.connectionId() != null)
            throw bad("Use the workspace replacement endpoint to save working state");
        return sessionStore.updateMetadata(id, req.name()).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }
    @PutMapping("/{id}/workspace") public ResponseEntity<Session> workspace(@PathVariable String id, @RequestBody JsonNode req) {
        if (!req.has("sqlText") || !req.get("sqlText").isTextual() || !req.has("dialect") || !req.get("dialect").isTextual()
                || !req.has("connectionId") || !req.has("workspaceSnapshotJson")) throw bad("Missing workspace fields");
        if (!req.get("connectionId").isNull() && !req.get("connectionId").isTextual()) throw bad("Invalid connectionId");
        JsonNode snap = req.get("workspaceSnapshotJson");
        if (!snap.isNull() && !snap.isTextual()) throw bad("Invalid workspaceSnapshotJson");
        String snapshot = snap.isNull() ? null : snap.textValue();
        String tree = snapshot == null ? null : snapshotTree(snapshot);
        return sessionStore.replaceWorkspace(id, req.get("sqlText").textValue(), req.get("dialect").textValue(),
            req.get("connectionId").isNull() ? null : req.get("connectionId").textValue(), snapshot, tree)
            .map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }
    private String snapshotTree(String snapshot) {
        try {
            JsonNode root = json.readTree(snapshot);
            if (root == null || !root.path("version").isIntegralNumber() || !List.of(1,2).contains(root.path("version").asInt()) || !root.has("graph") || !root.has("treeProjection")) throw bad("Invalid snapshot version or shape");
            validateSnapshot(root);
            JsonNode tree = root.get("treeProjection");
            if (root.get("graph").isNull()) {
                if (!tree.isNull()) throw bad("Empty graph must have empty tree");
                return null;
            }
            if (!tree.isObject() || !tree.path("id").isTextual() || !tree.path("children").isArray()) throw bad("Invalid snapshot tree");
            return json.writeValueAsString(tree);
        } catch (ResponseStatusException e) { throw e; }
        catch (Exception e) { throw bad("Invalid workspace snapshot JSON"); }
    }
    private record Edge(String source, String target, String rule) {}
    private record RuleKey(String source, String rule) {}
    private void validateSnapshot(JsonNode s) {
        if (!s.path("parsedSql").isTextual() || !List.of("postgresql","mysql","sqlserver","oracle").contains(s.path("dialect").asText())
            || !List.of("input","browse").contains(s.path("viewerMode").asText())
            || !List.of("data","performance").contains(s.path("workspaceMode").asText())
            || !s.has("selectedNodeId") || !(s.get("selectedNodeId").isNull() || s.get("selectedNodeId").isTextual())) throw bad("Invalid workspace context");
        JsonNode g = s.get("graph");
        if (g.isNull()) {
            if (!s.get("selectedNodeId").isNull()) throw bad("Empty graph has a selection");
            return;
        }
        if (!g.path("nodes").isArray() || !g.path("edges").isArray() || !g.path("ruleResults").isArray()
            || !List.of("recorded","legacy-tree").contains(g.path("completeness").asText())) throw bad("Invalid graph shape");
        Map<String,JsonNode> nodeById = new HashMap<>();
        Set<String> ids = new HashSet<>();
        for (JsonNode n : g.get("nodes")) {
            validateNode(n);
            nodeById.put(n.path("id").asText(),n);
            if (!n.get("children").isEmpty() || !ids.add(n.get("id").asText())) throw bad("Duplicate or nested graph node");
        }
        String rootId = g.path("rootNodeId").asText();
        if (!ids.contains(rootId)) throw bad("Missing graph root");
        Set<Edge> edges = new HashSet<>();
        Map<String,List<String>> outgoing = new HashMap<>();
        for (JsonNode e : g.get("edges")) {
            Edge edge = new Edge(e.path("sourceNodeId").asText(),e.path("targetNodeId").asText(),e.path("ruleName").asText());
            if (!ids.contains(edge.source()) || !ids.contains(edge.target()) || !e.path("ruleName").isTextual() || !edges.add(edge)) throw bad("Invalid graph edge");
            outgoing.computeIfAbsent(edge.source(), k -> new ArrayList<>()).add(edge.target());
        }
        Set<Edge> recorded = new HashSet<>(); Set<RuleKey> rules = new HashSet<>();
        for (JsonNode r : g.get("ruleResults")) {
            String source = r.path("sourceNodeId").asText(), rule = r.path("ruleName").asText();
            if (!ids.contains(source) || !r.path("ruleName").isTextual() || !r.path("childIds").isArray() || !rules.add(new RuleKey(source,rule))) throw bad("Invalid rule record");
            if(s.path("version").asInt()==2 && !"LEGACY".equals(r.path("status").asText())) validateTransforms(r,nodeById);
            for (JsonNode child : r.get("childIds")) {
                Edge edge = new Edge(source,child.asText(),rule);
                if (!child.isTextual() || !edges.contains(edge) || !recorded.add(edge)) throw bad("Rule record has invalid edge");
            }
        }
        if (!recorded.equals(edges)) throw bad("Unrecorded graph edge");
        Set<String> visiting = new HashSet<>(), visited = new HashSet<>();
        for (String id : ids) validateAcyclic(id,outgoing,visiting,visited);
        JsonNode tree = s.get("treeProjection");
        if (!rootId.equals(tree.path("id").asText())) throw bad("Invalid projection root");
        validateProjection(tree,ids,edges,new HashSet<>());
    }
    private void validateTransforms(JsonNode r,Map<String,JsonNode> nodes) {
        String source=r.path("sourceNodeId").asText();
        String hash=com.bitdata.sqlworkbench.fragment.FragmentIdentity.sha256Hex(nodes.get(source).path("sqlFragment").asText());
        if(!List.of("COMPLETE","PARTIAL","NOT_APPLICABLE","FAILED").contains(r.path("status").asText()) || !hash.equals(r.path("sourceSqlHash").asText())
            || !r.path("outputs").isArray() || !r.path("issues").isArray()) throw bad("Invalid transform record");
        Set<Integer> positions=new HashSet<>();Set<String> targets=new HashSet<>();
        for(JsonNode o:r.get("outputs")) {
            String target=o.path("targetNodeId").asText();JsonNode e=o.path("effect");
            if(!nodes.containsKey(target) || !source.equals(o.path("sourceNodeId").asText()) || !r.path("ruleName").equals(o.path("ruleName"))
                || !o.path("ordinal").isIntegralNumber() || o.path("ordinal").asInt()<1 || !positions.add(o.path("ordinal").asInt())
                || !hash.equals(e.path("sourceSqlHash").asText()) || !com.bitdata.sqlworkbench.fragment.FragmentIdentity.sha256Hex(nodes.get(target).path("sqlFragment").asText()).equals(e.path("targetSqlHash").asText())
                || !List.of("PRESERVED","DETACHED","UNKNOWN").contains(e.path("context").asText()) || !e.path("changes").isArray() || !e.path("retained").isArray() || !e.path("limitations").isArray()) throw bad("Invalid transform output");
            targets.add(target);
        }
        Set<String> children=new HashSet<>();r.get("childIds").forEach(n->children.add(n.asText()));
        if(!targets.equals(children) || "FAILED".equals(r.path("status").asText())&&!children.isEmpty()) throw bad("Transform disagrees with graph");
    }
    private void validateNode(JsonNode n) {
        if (!n.path("id").isTextual() || n.get("id").asText().isBlank() || !n.path("sqlFragment").isTextual()
            || !n.path("label").isTextual() || !n.path("executable").isBoolean() || !n.path("children").isArray()) throw bad("Invalid fragment node");
    }
    private void validateAcyclic(String id, Map<String,List<String>> outgoing, Set<String> visiting, Set<String> visited) {
        if (visiting.contains(id)) throw bad("Cyclic graph");
        if (visited.contains(id)) return;
        visiting.add(id);
        for (String child : outgoing.getOrDefault(id,List.of())) validateAcyclic(child,outgoing,visiting,visited);
        visiting.remove(id); visited.add(id);
    }
    private void validateProjection(JsonNode n, Set<String> ids, Set<Edge> edges, Set<String> ancestors) {
        validateNode(n);
        String id=n.get("id").asText();
        if (!ids.contains(id) || !ancestors.add(id)) throw bad("Invalid projection node");
        for (JsonNode c : n.get("children")) {
            if (edges.stream().noneMatch(e -> e.source().equals(id) && e.target().equals(c.path("id").asText()))) throw bad("Projection invents edge");
            validateProjection(c,ids,edges,new HashSet<>(ancestors));
        }
    }
    private static boolean blank(String s) { return s == null || s.isBlank(); }
    public record WorkspaceError(boolean degraded, String errorReason) {}
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<WorkspaceError> invalidWorkspace(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode()).body(new WorkspaceError(true, e.getReason()));
    }
    private static ResponseStatusException bad(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
    @DeleteMapping("/{id}") public ResponseEntity<Void> delete(@PathVariable String id) {
        sessionStore.delete(id); return ResponseEntity.noContent().build();
    }
}
