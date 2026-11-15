package com.bitdata.sqlworkbench.sql.diff;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.apache.calcite.sql.parser.SqlParseException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Spec §4.3 / §6.1 — only new endpoint required for LineagePanel.
 * Wraps {@link ChangeAttribution} for renderer consumption.
 */
@RestController
@RequestMapping("/api/diff")
public class DiffController {

    public record DiffRequest(
        @JsonProperty("before") String before,
        @JsonProperty("after")  String after,
        @JsonProperty("dialect") String dialect,
        com.bitdata.sqlworkbench.session.IdentityContext identityContext,
        java.util.List<com.bitdata.sqlworkbench.session.NodeProof> nodes
    ) implements com.bitdata.sqlworkbench.session.IdentityBoundRequest {
        public DiffRequest(String before, String after, String dialect) {
            this(before,after,dialect,null,null);
        }
    }

    public record DiffResponse(
        @JsonProperty("ops") List<OpDto> ops,
        @JsonProperty("degraded") boolean degraded,
        @JsonProperty("diffMethod") String diffMethod,
        @JsonProperty("errorReason") String errorReason
    ) {
        public DiffResponse(List<OpDto> ops, boolean degraded, String diffMethod) { this(ops,degraded,diffMethod,null); }
    }

    public record OpDto(
        @JsonProperty("kind")     String kind,
        @JsonProperty("nodeType") String nodeType,
        @JsonProperty("path")     String path,
        @JsonProperty("oldText")  String oldText,
        @JsonProperty("newText")  String newText,
        @JsonProperty("displayScope") String displayScope,
        @JsonProperty("comparisonKey") String comparisonKey
    ) {
        public OpDto(String kind,String nodeType,String path,String oldText,String newText) {
            this(kind,nodeType,path,oldText,newText,"WHOLE_SQL_CONTEXT",null);
        }

    }

    @PostMapping
    public ResponseEntity<?> diff(@RequestBody DiffRequest request) {
        if (request == null
            || request.before() == null || request.before().isBlank()
            || request.after() == null  || request.after().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                "code", "VALIDATION_ERROR",
                "message", "before and after are required and must be non-blank"
            ));
        }
        try {
            String dialect = request.dialect() != null && !request.dialect().isBlank()
                ? request.dialect() : "postgresql";
            DiffResult result = ChangeAttribution.attributeChangeDetailed(
                request.before(), request.after(), dialect);
            var display = StructuralSqlCodec.display(new StructuralSqlCodec.Delta(result.ops(),result.degraded(),
                result.errorReason(),result.method()),request.before(),request.after(),dialect);
            List<OpDto> dtos = display.stream().map(op -> new OpDto(op.kind(),op.nodeType(),op.path(),
                op.oldSql(),op.newSql(),op.displayScope(),op.comparisonKey())).toList();
            // P2-13: surface degradation so the frontend can warn the user
            // that attribution precision dropped (path-set vs LCS).
            return ResponseEntity.ok(
                new DiffResponse(dtos, result.degraded(), result.method(),result.errorReason()));
        } catch (SqlParseException e) {
            return ResponseEntity.unprocessableEntity().body(Map.of(
                "code", "SQL_PARSE_ERROR",
                "message", e.getMessage()
            ));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of(
                "code", "INTERNAL_ERROR",
                "message", "diff failed: " + e.getMessage()
            ));
        }
    }
}
