package com.bitdata.sqlworkbench.sql;

import com.bitdata.sqlworkbench.sql.model.*;
import org.apache.calcite.sql.parser.SqlParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/sql")
public class SqlParseController {

    private static final Logger log = LoggerFactory.getLogger(SqlParseController.class);

    private final SqlParseService parseService;
    private final SqlPositionService positionService;

    public SqlParseController(SqlParseService parseService, SqlPositionService positionService) {
        this.parseService = parseService;
        this.positionService = positionService;
    }

    /**
     * Parse SQL and return the root node with available decomposition options.
     * No decomposition rules are applied — the user picks which rule to use next.
     */
    @PostMapping("/parse")
    public ResponseEntity<?> parse(@RequestBody ParseRequest request) {
        try {
            ParseResult result = parseService.parse(request);
            if (!result.errors().isEmpty()) {
                return ResponseEntity.unprocessableEntity().body(result);
            }
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of(
                "code", "VALIDATION_ERROR",
                "message", e.getMessage()
            ));
        } catch (Exception e) {
            log.error("Unexpected error during SQL parse", e);
            return ResponseEntity.internalServerError().body(Map.of(
                "code", "INTERNAL_ERROR",
                "message", "An unexpected error occurred while parsing SQL"
            ));
        }
    }

    /**
     * Apply a single decomposition rule to a SQL fragment.
     * Returns the child FragmentNodes produced by the rule.
     */
    @PostMapping("/decompose")
    public ResponseEntity<?> decompose(@RequestBody DecomposeRequest request) {
        if (request.sqlFragment() == null || request.sqlFragment().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                "code", "VALIDATION_ERROR",
                "message", "sqlFragment is required"
            ));
        }
        if (request.ruleName() == null || request.ruleName().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                "code", "VALIDATION_ERROR",
                "message", "ruleName is required"
            ));
        }
        try {
            DecompositionResult.Batch children = parseService.decomposeDetailed(
                request.sqlFragment(),
                request.dialect() != null ? request.dialect() : "postgresql",
                request.ruleName(),
                request.sourceNodeId(),
                request.connectionId()
            );
            return ResponseEntity.ok(children);
        } catch (SqlParseException e) {
            return ResponseEntity.unprocessableEntity().body(Map.of(
                "code", "SQL_PARSE_ERROR",
                "message", e.getMessage()
            ));
        } catch (Exception e) {
            log.error("Decompose failed", e);
            return ResponseEntity.internalServerError().body(Map.of(
                "code", "INTERNAL_ERROR",
                "message", "Decomposition failed: " + e.getMessage()
            ));
        }
    }

    /**
     * Return available decomposition options for a SQL fragment.
     */
    @PostMapping("/decompose-options")
    public ResponseEntity<?> getDecomposeOptions(@RequestBody DecomposeOptionsRequest request) {
        if (request.sqlFragment() == null || request.sqlFragment().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                "code", "VALIDATION_ERROR",
                "message", "sqlFragment is required"
            ));
        }
        try {
            DecompositionResult.Options options = parseService.scanOptions(
                request.sqlFragment(),
                request.dialect() != null ? request.dialect() : "postgresql",
                request.connectionId()
            );
            return ResponseEntity.ok(options);
        } catch (SqlParseException e) {
            return ResponseEntity.unprocessableEntity().body(Map.of(
                "code", "SQL_PARSE_ERROR",
                "message", e.getMessage()
            ));
        } catch (Exception e) {
            log.error("Get decompose options failed", e);
            return ResponseEntity.internalServerError().body(Map.of(
                "code", "INTERNAL_ERROR",
                "message", e.getMessage()
            ));
        }
    }

    /**
     * Return CTE dependency information for a SQL query.
     * Shows which CTEs reference which other CTEs.
     */
    @PostMapping("/dependencies")
    public ResponseEntity<?> getCteDependencies(@RequestBody CteDependencyRequest request) {
        if (request.sql() == null || request.sql().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                "code", "VALIDATION_ERROR",
                "message", "sql is required"
            ));
        }
        try {
            var result = parseService.getCteDependencies(
                request.sql(),
                request.dialect() != null ? request.dialect() : "postgresql"
            );
            return ResponseEntity.ok(Map.of(
                "ctes", result.ctes(),
                "bodyDependsOn", result.bodyDependsOn()
            ));
        } catch (SqlParseException e) {
            return ResponseEntity.unprocessableEntity().body(Map.of(
                "code", "SQL_PARSE_ERROR",
                "message", e.getMessage()
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of(
                "code", "VALIDATION_ERROR",
                "message", e.getMessage()
            ));
        } catch (Exception e) {
            log.error("Get CTE dependencies failed", e);
            return ResponseEntity.internalServerError().body(Map.of(
                "code", "INTERNAL_ERROR",
                "message", e.getMessage()
            ));
        }
    }

    /**
     * Return positions of all significant SQL nodes for clickable node selection.
     */
    @PostMapping("/positions")
    public ResponseEntity<?> getSqlPositions(@RequestBody ParseRequest request) {
        if (request.sql() == null || request.sql().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                "code", "VALIDATION_ERROR",
                "message", "sql is required"
            ));
        }
        try {
            var positions = positionService.extractPositions(
                request.sql(),
                request.dialect() != null ? request.dialect() : "postgresql"
            );
            return ResponseEntity.ok(Map.of("nodes", positions));
        } catch (Exception e) {
            log.error("Failed to extract SQL positions", e);
            return ResponseEntity.internalServerError().body(Map.of(
                "code", "INTERNAL_ERROR",
                "message", "Failed to extract SQL positions: " + e.getMessage()
            ));
        }
    }

    /**
     * Compute the set of JOINs present in the parent SQL fragment but absent
     * from the child fragment. Used by the "Add JOIN" composition feature to
     * drive the context menu of decomposed fragments.
     */
    @PostMapping("/join-diff")
    public ResponseEntity<?> joinDiff(@RequestBody JoinDiffRequest request) {
        if (request.parentSql() == null || request.parentSql().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                "code", "VALIDATION_ERROR",
                "message", "parentSql is required"
            ));
        }
        if (request.childSql() == null || request.childSql().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                "code", "VALIDATION_ERROR",
                "message", "childSql is required"
            ));
        }
        try {
            JoinDiffResponse result = parseService.joinDiff(request);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("join-diff failed", e);
            return ResponseEntity.internalServerError().body(Map.of(
                "code", "INTERNAL_ERROR",
                "message", "join-diff failed: " + e.getMessage()
            ));
        }
    }

    /**
     * Generic composition endpoint for Phase 2 (Add WHERE / Add Aggregate).
     * Dispatches by {@code type} to the corresponding extractor.
     */
    @PostMapping("/fragment-diff")
    public ResponseEntity<?> fragmentDiff(@RequestBody FragmentDiffRequest request) {
        if (request.parentSql() == null || request.parentSql().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                "code", "VALIDATION_ERROR",
                "message", "parentSql is required"
            ));
        }
        if (request.childSql() == null || request.childSql().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                "code", "VALIDATION_ERROR",
                "message", "childSql is required"
            ));
        }
        if (request.type() == null || request.type().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                "code", "VALIDATION_ERROR",
                "message", "type is required (join | where | aggregate)"
            ));
        }
        try {
            FragmentDiffRequest.FragmentDiffResponse result = parseService.fragmentDiff(request);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("fragment-diff failed", e);
            return ResponseEntity.internalServerError().body(Map.of(
                "code", "INTERNAL_ERROR",
                "message", "fragment-diff failed: " + e.getMessage()
            ));
        }
    }
}
