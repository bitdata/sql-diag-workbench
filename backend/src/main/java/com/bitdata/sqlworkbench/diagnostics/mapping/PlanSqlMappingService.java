package com.bitdata.sqlworkbench.diagnostics.mapping;

import com.bitdata.sqlworkbench.diagnostics.DiagnosticFinding;
import com.bitdata.sqlworkbench.execution.model.ExecutionResult;
import com.bitdata.sqlworkbench.execution.model.PlanDetails;
import com.bitdata.sqlworkbench.sql.SqlPositionService;
import com.bitdata.sqlworkbench.sql.SqlDialectResolver;
import com.bitdata.sqlworkbench.connection.DatabaseStrategyFactory;
import com.bitdata.sqlworkbench.sql.model.FragmentNode;
import com.bitdata.sqlworkbench.sql.model.SqlNodePosition;
import org.springframework.stereotype.Service;
import org.apache.calcite.avatica.util.Casing;
import org.apache.calcite.sql.SqlCall;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlNodeList;
import org.apache.calcite.sql.SqlLiteral;
import org.apache.calcite.sql.SqlDynamicParam;
import org.apache.calcite.sql.SqlIntervalQualifier;
import org.apache.calcite.sql.SqlDataTypeSpec;
import org.apache.calcite.sql.SqlBasicTypeNameSpec;
import org.apache.calcite.sql.SqlFunction;
import org.apache.calcite.sql.parser.SqlParser;
import org.apache.calcite.util.Litmus;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

import static com.bitdata.sqlworkbench.diagnostics.mapping.PlanSqlMapping.*;

/**
 * Builds a conservative mapping for one retained plan observation.  The service
 * deliberately emits UNKNOWN edges when a physical rewrite removes a unique
 * correspondence; callers can therefore distinguish missing evidence from an
 * empty query.
 */
@Service
public class PlanSqlMappingService {
    private final SqlPositionService positions;
    private final SqlDialectResolver dialects;

    public PlanSqlMappingService(SqlPositionService positions) {
        this(positions, new SqlDialectResolver(DatabaseStrategyFactory.withBuiltinStrategies()));
    }

    @org.springframework.beans.factory.annotation.Autowired
    public PlanSqlMappingService(SqlPositionService positions, SqlDialectResolver dialects) {
        this.positions = positions;
        this.dialects = dialects;
    }

    public PlanSqlMapping map(String executionId, String dialect, String submittedSql,
                              ExecutionResult result, FragmentNode fragment,
                              List<DiagnosticFinding> findings) {
        String hash = sha256(submittedSql);
        String captureKey = executionId + ":" + dialect + ":" + hash;
        List<PlanMappingEdge> edges = new ArrayList<>();
        List<MappingIssue> issues = new ArrayList<>();
        boolean degraded = false;
        String errorReason = null;

        if (executionId == null || executionId.isBlank()) {
            issues.add(new MappingIssue("MAPPING_SCOPE_MISMATCH", null, "executionId is required"));
            return failed(executionId, dialect, submittedSql, edges, issues, "MAPPING_SCOPE_MISMATCH");
        }
        if (submittedSql == null || submittedSql.isBlank()) {
            issues.add(new MappingIssue("SQL_POSITION_PARSE_FAILED", null, "submitted SQL is empty"));
            return failed(executionId, dialect, submittedSql, edges, issues, "SQL_POSITION_PARSE_FAILED");
        }
        if (result == null || result.plan() == null) {
            issues.add(new MappingIssue("PLAN_SOURCE_UNAVAILABLE", null, "plan tree is unavailable"));
            return failed(executionId, dialect, submittedSql, edges, issues, "PLAN_SOURCE_UNAVAILABLE");
        }

        var capture = result.planCapture();
        if (!Objects.equals(executionId, result.executionId()) || capture == null
            || !Objects.equals(capture.submittedSql(), submittedSql) || !Objects.equals(capture.dialect(), dialect)) {
            issues.add(new MappingIssue("MAPPING_SCOPE_MISMATCH", null, "capture does not match execution, SQL and dialect"));
            return failed(executionId, dialect, submittedSql, edges, issues, "MAPPING_SCOPE_MISMATCH");
        }

        List<SqlNodePosition> sqlPositions;
        MappingSqlIndex index;
        try {
            sqlPositions = positions.extractPositions(submittedSql, dialect);
            index = MappingSqlIndex.parse(submittedSql, dialect, dialects);
        } catch (Exception failure) {
            issues.add(new MappingIssue("SQL_POSITION_PARSE_FAILED", null, "submitted SQL AST could not be read"));
            return failed(executionId, dialect, submittedSql, edges, issues, "SQL_POSITION_PARSE_FAILED");
        }
        if (sqlPositions.isEmpty()) {
            issues.add(new MappingIssue("SQL_POSITION_PARSE_FAILED", null, "no AST positions were produced"));
            degraded = true;
            errorReason = "SQL_POSITION_PARSE_FAILED";
        }

        PlanDetails details = result.planCapture() == null ? null : result.planCapture().details();
        if (details != null && details.errorReason() != null) {
            issues.add(new MappingIssue(details.errorReason(), null, "plan evidence is degraded"));
            degraded = true;
            errorReason = details.errorReason();
        }

        List<FragmentNode> fragments = flatten(fragment);
        if (fragment != null && !sameFragmentScope(submittedSql, fragment.sqlFragment(), dialect, issues)) {
            issues.add(new MappingIssue("FRAGMENT_SCOPE_MISMATCH", null, "fragment root is not the submitted SQL; fragment edges are unavailable"));
            fragments = List.of();
        }
        boolean complete = !sqlPositions.isEmpty() && capture.version() == 1 && "COMPLETE".equals(capture.parseStatus())
            && capture.issues().isEmpty() && !result.degraded();
        if (!complete) issues.add(new MappingIssue("PLAN_CAPTURE_DEGRADED", null, "capture is partial, synthetic or unsupported"));
        var fragmentIndexes = indexFragments(fragments, dialect, issues);
        walk(result.plan(), index, submittedSql, fragmentIndexes, findings == null ? List.of() : findings,
            details, complete, true, edges, issues);
        boolean issueDegraded = !issues.isEmpty();
        if (errorReason == null && issueDegraded) errorReason = issues.get(0).code();
        return new PlanSqlMapping(1, executionId, captureKey, dialect, hash, edges,
            degraded || issueDegraded, errorReason, issues);
    }

    private record IndexedFragment(FragmentNode fragment, MappingSqlIndex index) {}

    private void walk(ExecutionResult.PlanNode node, MappingSqlIndex index,
                      String sql, List<IndexedFragment> fragments, List<DiagnosticFinding> findings,
                      PlanDetails details, boolean complete, boolean root, List<PlanMappingEdge> edges, List<MappingIssue> issues) {
        if (node == null) return;
        String path = node.metrics() == null ? null : node.metrics().sourcePath();
        if (path == null || path.isBlank()) {
            issues.add(new MappingIssue("PLAN_NODE_PATH_UNAVAILABLE", null, "plan node has no execution-scoped sourcePath"));
            edges.add(new PlanMappingEdge(null, TargetKind.SQL_SPAN, null, null, Confidence.UNKNOWN,
                Relation.PLAN_TO_SQL, "sourcePath is unavailable"));
            for (ExecutionResult.PlanNode child : node.children()) walk(child, index, sql, fragments, findings, details, complete, false, edges, issues);
            return;
        }

        boolean rawAvailable = details != null && details.version() == 1 && details.errorReason() == null
            && details.nodes().stream().anyMatch(n -> Objects.equals(path, n.sourcePath()) && n.raw() != null && !n.raw().isBlank());
        boolean usable = complete && rawAvailable && node.metrics().version() == 1
            && "DATABASE_NODE".equals(node.metrics().origin());
        if (!rawAvailable) issues.add(new MappingIssue("PLAN_NODE_EVIDENCE_UNAVAILABLE", path, "raw evidence is unavailable for this node"));
        if (root) edges.add(new PlanMappingEdge(path, TargetKind.SQL_SPAN, null,
            new SqlSpan("submitted_root", "QUERY", "Submitted SQL", 0, sql.length()),
            usable ? Confidence.EXACT : Confidence.UNKNOWN, Relation.PLAN_TO_SQL, "submitted query observation; not an operator-to-clause identity"));
        List<MappingSqlIndex.Relation> candidates = index.candidates(node.tableName());
        if (candidates.isEmpty()) {
            edges.add(new PlanMappingEdge(path, TargetKind.SQL_SPAN, null, null, Confidence.UNKNOWN,
                Relation.PLAN_TO_SQL, reason(node, details, "no unique SQL span evidence")));
        } else {
            for (var candidate : candidates) {
                edges.add(new PlanMappingEdge(path, TargetKind.SQL_SPAN, null, candidate.span(), usable ? Confidence.INFERRED : Confidence.UNKNOWN,
                    Relation.PLAN_TO_SQL, "AST relation or alias candidate; optimizer correspondence is unproven"));
            }
        }

        var fragmentCandidates = fragments.stream().filter(f -> !f.index().candidates(node.tableName()).isEmpty())
            .map(IndexedFragment::fragment).toList();
        if (fragmentCandidates.isEmpty()) {
            edges.add(new PlanMappingEdge(path, TargetKind.FRAGMENT, null, null, Confidence.UNKNOWN,
                Relation.PLAN_TO_FRAGMENT, "no fragment contains the plan relation"));
        } else {
            for (FragmentNode candidate : fragmentCandidates) {
                boolean fragmentDegraded = candidate.canonicalizationError() != null || !candidate.executable();
                if (fragmentDegraded) issues.add(new MappingIssue("FRAGMENT_IDENTITY_DEGRADED", path, "fragment identity or execution is degraded: " + candidate.id()));
                String reason = candidate.canonicalizationError() != null
                    ? "fragment identity degraded: " + candidate.canonicalizationError()
                    : "AST relation candidate: " + candidate.label() + " (" + candidate.type() + ")";
                edges.add(new PlanMappingEdge(path, TargetKind.FRAGMENT, candidate.id(), null,
                    usable && !fragmentDegraded ? Confidence.INFERRED : Confidence.UNKNOWN,
                    Relation.PLAN_TO_FRAGMENT, reason));
            }
        }

        for (DiagnosticFinding finding : findings) {
            if (finding == null) {
                issues.add(new MappingIssue("FINDING_EVIDENCE_UNAVAILABLE", path, "null finding was skipped"));
                continue;
            }
            if (Objects.equals(path, finding.planNodePath())) {
                edges.add(new PlanMappingEdge(path, TargetKind.DIAGNOSTIC_EVIDENCE, finding.id(), null,
                    usable && !finding.degraded() ? Confidence.INFERRED : Confidence.UNKNOWN,
                    Relation.PLAN_TO_EVIDENCE, "client-supplied finding path candidate; finding execution identity is not independently attested"));
            }
        }
        for (ExecutionResult.PlanNode child : node.children()) walk(child, index, sql, fragments, findings, details, complete, false, edges, issues);
    }

    private List<IndexedFragment> indexFragments(List<FragmentNode> fragments, String dialect, List<MappingIssue> issues) {
        List<IndexedFragment> candidates = new ArrayList<>();
        for (var fragment : fragments) {
            if (fragment.sqlFragment() == null) continue;
            try {
                candidates.add(new IndexedFragment(fragment, MappingSqlIndex.parse(fragment.sqlFragment(), dialect, dialects)));
            } catch (Exception failure) {
                issues.add(new MappingIssue("FRAGMENT_SQL_PARSE_FAILED", null, "fragment SQL AST could not be read: " + fragment.id()));
            }
        }
        return candidates;
    }

    private static String reason(ExecutionResult.PlanNode node, PlanDetails details, String fallback) {
        if (details != null && details.nodes().stream().noneMatch(n -> Objects.equals(n.sourcePath(), node.metrics() == null ? null : node.metrics().sourcePath())))
            return "raw plan evidence has no matching sourcePath";
        return fallback;
    }

    private static List<FragmentNode> flatten(FragmentNode root) {
        if (root == null) return List.of();
        List<FragmentNode> result = new ArrayList<>();
        flatten(root, result);
        return result;
    }

    private boolean sameFragmentScope(String sql, String fragmentSql, String dialect, List<MappingIssue> issues) {
        if (Objects.equals(sql, fragmentSql)) return true;
        if (fragmentSql == null) return false;
        try {
            var config = dialects.parserConfig(dialect)
                .withQuotedCasing(Casing.UNCHANGED).withUnquotedCasing(Casing.UNCHANGED);
            var submitted = SqlParser.create(sql, config).parseQuery();
            var fragment = SqlParser.create(fragmentSql, config).parseQuery();
            // Scope identity is deliberately stricter than semantic normalization.
            // equalsDeep preserves spelling but ignores identifier quote flags.
            return submitted.equalsDeep(fragment, Litmus.IGNORE)
                && submitted.toString().equals(fragment.toString())
                && sameIdentifierQuoting(submitted, fragment);
        } catch (Exception failure) {
            issues.add(new MappingIssue("FRAGMENT_SQL_PARSE_FAILED", null, "identity-preserving scope comparison failed"));
            return false;
        }
    }

    private static boolean sameIdentifierQuoting(SqlNode left, SqlNode right) {
        if (left == null || right == null) return left == right;
        if (left.getClass() != right.getClass()) return false;
        if (left instanceof SqlIdentifier a) {
            if (!(right instanceof SqlIdentifier b) || !a.names.equals(b.names)) return false;
            for (int i = 0; i < a.names.size(); i++) {
                if (a.isComponentQuoted(i) != b.isComponentQuoted(i)) return false;
            }
            return true;
        } else if (left instanceof SqlCall a) {
            if (!(right instanceof SqlCall b)) return false;
            if (a.getOperator() instanceof SqlFunction af) {
                if (!(b.getOperator() instanceof SqlFunction bf)
                    || !sameIdentifierQuoting(af.getSqlIdentifier(), bf.getSqlIdentifier())) return false;
            }
            return sameOperandQuoting(a.getOperandList(), b.getOperandList());
        } else if (left instanceof SqlNodeList a) {
            if (!(right instanceof SqlNodeList b)) return false;
            return sameOperandQuoting(a.getList(), b.getList());
        }
        if (left instanceof SqlDataTypeSpec a && right instanceof SqlDataTypeSpec b) {
            // UDT/row/collection types hold identifiers outside SqlNode operands.
            // Until their identity is independently checked, only exact-source
            // scope can accept them (the fast path above).
            return a.getTypeNameSpec().getClass() == SqlBasicTypeNameSpec.class
                && b.getTypeNameSpec().getClass() == SqlBasicTypeNameSpec.class;
        }
        // Calcite 1.37 SqlNode direct subclasses are the handled structural
        // nodes above and these serialized/equalsDeep-checked scalar leaves.
        // Future/unrecognized forms must not silently establish scope identity.
        return left instanceof SqlLiteral || left instanceof SqlDynamicParam || left instanceof SqlIntervalQualifier;
    }

    private static boolean sameOperandQuoting(List<SqlNode> left, List<SqlNode> right) {
        if (left.size() != right.size()) return false;
        for (int i = 0; i < left.size(); i++) {
            if (!sameIdentifierQuoting(left.get(i), right.get(i))) return false;
        }
        return true;
    }

    private static void flatten(FragmentNode node, List<FragmentNode> out) {
        out.add(node);
        for (FragmentNode child : node.children() == null ? List.<FragmentNode>of() : node.children()) flatten(child, out);
    }

    private static PlanSqlMapping failed(String executionId, String dialect, String sql,
                                         List<PlanMappingEdge> edges, List<MappingIssue> issues, String reason) {
        String hash = sha256(sql);
        return new PlanSqlMapping(1, executionId, executionId + ":" + dialect + ":" + hash, dialect, hash,
            edges, true, reason, issues);
    }

    static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("MAPPING_HASH_FAILED", e);
        }
    }
}
