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
        @JsonProperty("dialect") String dialect
    ) {}

    public record DiffResponse(
        @JsonProperty("ops") List<OpDto> ops,
        @JsonProperty("degraded") boolean degraded,
        @JsonProperty("diffMethod") String diffMethod
    ) {}

    public record OpDto(
        @JsonProperty("kind")     String kind,
        @JsonProperty("nodeType") String nodeType,
        @JsonProperty("path")     String path,
        @JsonProperty("oldText")  String oldText,
        @JsonProperty("newText")  String newText
    ) {
        static OpDto from(ChangeOp op) {
            return new OpDto(
                op.kind().name(),
                op.nodeType(),
                op.path(),
                op.oldText(),
                op.newText()
            );
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
            List<OpDto> dtos = result.ops().stream().map(OpDto::from).toList();
            // P2-13: surface degradation so the frontend can warn the user
            // that attribution precision dropped (path-set vs LCS).
            return ResponseEntity.ok(
                new DiffResponse(dtos, result.degraded(), result.method()));
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
