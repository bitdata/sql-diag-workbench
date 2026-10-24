package com.bitdata.sqlworkbench.fragment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * REST controller for fragment operations.
 *
 * <p>Exposes {@code POST /api/fragment/create-user-edit} — a unified endpoint
 * that handles identity computation, canonicalization, and NAVIGATE/EDIT
 * classification in a single call.
 */
@RestController
@RequestMapping("/api/fragment")
public class FragmentController {

    private static final Logger log = LoggerFactory.getLogger(FragmentController.class);

    private final UserEditFragmentService userEditService;

    public FragmentController(UserEditFragmentService userEditService) {
        this.userEditService = userEditService;
    }

    public record CreateUserEditRequest(
        String sql,
        String parentFragmentId,
        List<String> dagContextIds,
        String dialect,
        String description
    ) {}

    @PostMapping("/create-user-edit")
    public ResponseEntity<?> createUserEdit(@RequestBody CreateUserEditRequest request) {
        // Validation
        if (request.sql() == null || request.sql().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                "code", "VALIDATION_ERROR",
                "message", "sql must not be null or blank"
            ));
        }
        if (request.dialect() == null || request.dialect().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                "code", "VALIDATION_ERROR",
                "message", "dialect must not be null or blank"
            ));
        }
        if (request.parentFragmentId() == null || request.parentFragmentId().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                "code", "VALIDATION_ERROR",
                "message", "parentFragmentId must not be null or blank"
            ));
        }

        try {
            Set<String> dagContext = request.dagContextIds() != null
                ? Set.copyOf(request.dagContextIds())
                : Set.of();

            var result = userEditService.createUserEdit(
                request.sql(),
                request.parentFragmentId(),
                dagContext,
                request.dialect(),
                request.description()
            );

            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("create-user-edit failed", e);
            return ResponseEntity.internalServerError().body(Map.of(
                "code", "INTERNAL_ERROR",
                "message", "An internal error occurred while creating the fragment"
            ));
        }
    }
}
