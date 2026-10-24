package com.bitdata.sqlworkbench.fragment;

import com.bitdata.sqlworkbench.sql.diff.SqlCanonicalizer;
import com.bitdata.sqlworkbench.sql.model.FragmentNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Set;

/**
 * Handles user-edit fragment creation with ADR-0001 identity and ADR-0002 classification.
 *
 * <p>Canonicalizes SQL, computes SHA-256 identity, and classifies the edit as
 * NAVIGATE (equivalent node already exists in DAG) or EDIT (new unique node).
 * A degraded canonical form (unknown node type, or parse failure) is classified
 * {@code "UNSUPPORTED"} and creates no node — it must not be given an identity.
 * Does not persist nodes — fragments live in session JSON.
 */
@Service
public class UserEditFragmentService {

    private static final Logger log = LoggerFactory.getLogger(UserEditFragmentService.class);

    /**
     * Result of a user-edit creation request.
     *
     * @param node the created or navigated-to fragment node; {@code null} when
     *             {@code classification} is {@code "UNSUPPORTED"}
     * @param classification "EDIT" for new nodes, "NAVIGATE" for existing equivalents,
     *                       "UNSUPPORTED" when canonicalization degraded
     * @param navigatedToExisting true if an equivalent node was already in the DAG
     * @param errorReason non-null exactly when the shape could not be canonicalized
     *                    without falling back (spec 2026-10-01 §4.2, rule #2). A
     *                    degraded result must never carry an identity: two different
     *                    unsupported SQLs used to hash to the same fragment id.
     */
    public record CreateUserEditResult(
        FragmentNode node,
        String classification,
        boolean navigatedToExisting,
        String errorReason
    ) {
        static CreateUserEditResult unsupported(String reason) {
            return new CreateUserEditResult(null, "UNSUPPORTED", false, reason);
        }
    }

    /**
     * Create a user-edited fragment node.
     *
     * @param sql raw SQL from the user
     * @param parentFragmentId the node being edited (operation tree parent)
     * @param dagContextIds all node IDs currently in the DAG (NAVIGATE check)
     * @param dialect SQL dialect for canonicalization
     */
    public CreateUserEditResult createUserEdit(
        String sql,
        String parentFragmentId,
        Set<String> dagContextIds,
        String dialect,
        String description
    ) {
        if (sql == null || sql.isBlank()) {
            return CreateUserEditResult.unsupported("sql must not be null or blank");
        }

        // 1. Canonicalize (P0 + P1). Both degradation sources end here, and both are
        //    refused rather than silently given an identity (spec §4.2 / D3):
        //      (a) parse failure          — used to hash the RAW sql (log-only降级);
        //      (b) UNKNOWN token present  — used to hash the content-free fallback
        //          string, which collided across every same-shaped unsupported query.
        SqlCanonicalizer.CanonicalForm form;
        try {
            form = SqlCanonicalizer.canonicalizeSqlWithStatus(sql, dialect);
        } catch (Exception e) {
            log.debug("canonicalizeSql failed: {}", e.getMessage());
            return CreateUserEditResult.unsupported("canonicalization failed: " + e.getMessage());
        }
        if (form.degraded()) {
            return CreateUserEditResult.unsupported(
                "unsupported node types: " + String.join(", ", form.unsupportedNodeTypes()));
        }
        String canonicalSql = form.canonicalSql();

        // 2. Compute identity (ADR-0001) — only on a non-degraded canonical form.
        String id = FragmentIdentity.computeId(canonicalSql, dialect);

        // 3. Check DAG context (ADR-0002 classification)
        if (dagContextIds.contains(id)) {
            // Equivalent node exists — navigate, don't create duplicate
            FragmentNode existing = FragmentNode.userEdit(
                id, "Navigate: existing", sql, canonicalSql,
                parentFragmentId, Instant.now().toString()
            );
            return new CreateUserEditResult(existing, "NAVIGATE", true, null);
        }

        // 4. EDIT: create new node
        String label = buildLabel(sql, description);

        FragmentNode node = FragmentNode.userEdit(
            id, label, sql, canonicalSql,
            parentFragmentId, Instant.now().toString()
        );

        return new CreateUserEditResult(node, "EDIT", false, null);
    }

    /**
     * Build a human-readable label from the SQL, truncated to 53 chars max.
     */
    private String buildLabel(String sql, String description) {
        if (description != null && !description.isBlank()) {
            String label = description.trim();
            if (label.length() > 53) {
                label = label.substring(0, 50) + "...";
            }
            return label;
        }
        String normalized = sql.replaceAll("\\s+", " ").trim();
        String label = "Edit: " + normalized;
        if (label.length() > 53) {
            label = label.substring(0, 50) + "...";
        }
        return label;
    }
}
