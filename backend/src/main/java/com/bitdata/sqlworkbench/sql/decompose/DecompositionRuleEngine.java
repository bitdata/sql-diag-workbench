package com.bitdata.sqlworkbench.sql.decompose;

import com.bitdata.sqlworkbench.fragment.FragmentIdentity;
import com.bitdata.sqlworkbench.sql.NodeToSql;
import com.bitdata.sqlworkbench.sql.DialectProfile;
import com.bitdata.sqlworkbench.sql.SqlDialectResolver;
import com.bitdata.sqlworkbench.sql.diff.SqlCanonicalizer;
import com.bitdata.sqlworkbench.sql.model.DecomposeOption;
import com.bitdata.sqlworkbench.sql.model.FragmentNode;
import com.bitdata.sqlworkbench.sql.model.FragmentType;
import com.bitdata.sqlworkbench.sql.model.SourceKind;
import com.bitdata.sqlworkbench.sql.model.TransformRecord;
import com.bitdata.sqlworkbench.sql.model.DecompositionResult.*;
import org.apache.calcite.sql.parser.SqlParser;

import org.apache.calcite.sql.*;
import org.apache.calcite.sql.dialect.MysqlSqlDialect;
import org.apache.calcite.sql.dialect.PostgresqlSqlDialect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;
import java.util.stream.IntStream;

/**
 * Orchestrates DecompositionRules against a parsed SqlNode.
 *
 * With the interactive decomposition model, the engine supports two modes:
 * 1. Full build — applies all rules at once (legacy, for tests)
 * 2. Single-rule decomposition — the user picks which rule to apply
 */
@Component
public class DecompositionRuleEngine {

    private static final Logger log = LoggerFactory.getLogger(DecompositionRuleEngine.class);

    private final List<DecompositionRule> rules;
    private final SqlDialectResolver resolver;

    public DecompositionRuleEngine(
        ClauseStrippingRule clauseRule,
        SplitSetOpRule splitRule,
        PredicateDecompositionRule predicateRule,
        CteExpansionRule cteRule,
        JoinSplitRule joinRule,
        SubqueryExtractionRule subqueryRule,
        SqlDialectResolver resolver
    ) {
        this.rules = List.of(clauseRule, splitRule, predicateRule, cteRule, joinRule, subqueryRule);
        this.resolver = resolver;
    }

    /**
     * Create a root FragmentNode from a parsed AST — no decomposition applied.
     */
    public FragmentNode createRootNode(SqlNode rootNode, String dialect) {
        String sql = sqlToString(rootNode, dialect);
        String id = computeNodeId(sql, dialect);
        return withLineageAndLineageFields(
            id,
            FragmentType.ORIGINAL,
            buildRootLabel(rootNode),
            sql,
            true,
            null,
            0,
            null,
            List.of(),
            List.of(),                                       // root has no parents
            new TransformRecord(TransformRecord.TransformKind.PARSE, null, "Parsed SQL", null),
            SourceKind.AUTO,
            null,                                            // editParentId
            dialect
        );
    }

    /**
     * Return all decomposition options available for a given AST node.
     */
    public List<DecomposeOption> getAvailableOptions(SqlNode rootNode, String dialect) {
        Options result = scanOptions(rootNode, dialect);
        if (!result.scan().issues().isEmpty()) throw new IllegalStateException(result.scan().issues().toString());
        return result.options();
    }

    public Options scanOptions(SqlNode rootNode, String dialect) {
        List<DecomposeOption> options = new ArrayList<>(); List<Issue> issues = new ArrayList<>();
        int failed=0;
        for (DecompositionRule rule : rules) {
            try {
                RuleEvaluation evaluation=rule.evaluate(rootNode,dialect);
                issues.addAll(evaluation.issues());
                if (!evaluation.fragments().isEmpty() || !evaluation.issues().isEmpty())
                    options.add(new DecomposeOption(rule.name(),rule.displayName(),rule.description(),rule.level()));
            } catch (Exception e) {
                failed++;
                issues.add(new Issue("RULE_FAILED",degradationMarker(e),rule.name(),"query",null,null));
                options.add(new DecomposeOption(rule.name(),rule.displayName(),rule.description(),rule.level()));
            }
        }
        return new Options(options,new Scan(issues.isEmpty()?"COMPLETE":failed==rules.size()?"FAILED":"PARTIAL",issues));
    }

    public Batch decomposeDetailed(SqlNode root, String sourceSql, String dialect, String ruleName, String sourceId) {
        return decomposeDetailed(root,root,sourceSql,dialect,ruleName,sourceId);
    }
    public Batch decomposeDetailed(SqlNode root, SqlNode original, String sourceSql, String dialect, String ruleName, String sourceId) {
        String parentId=sourceId==null||sourceId.isBlank()?computeNodeId(sourceSql,dialect):sourceId;
        DecompositionRule rule=rules.stream().filter(r->r.name().equals(ruleName)).findFirst().orElse(null);
        if(rule==null) return new Batch("FAILED",List.of(),List.of(),List.of(new Issue("RULE_FAILED","Unknown rule",ruleName,"query",parentId,null)));
        try {
            RuleEvaluation evaluation=rule.evaluate(root,dialect);
            List<Issue> issues=new ArrayList<>(evaluation.issues());List<FragmentNode> children=new ArrayList<>();List<Output> outputs=new ArrayList<>();
            for(int i=0;i<evaluation.fragments().size();i++) {
                DecomposedFragment df=evaluation.fragments().get(i);
                if(!df.executable()) { issues.add(new Issue("UNSUPPORTED_SCOPE",df.executionHint()==null?"Non-executable scope":df.executionHint(),ruleName,"query",null,df.sqlFragment()));continue; }
                try {
                    SqlNode target=SqlParser.create(df.sqlFragment(),resolver.parserConfig(dialect)).parseQuery();
                    if ("mysql".equalsIgnoreCase(dialect) && hasOffsetWithoutLimit(target)) {
                        issues.add(new Issue("UNSUPPORTED_SCOPE", "MySQL requires LIMIT with OFFSET; candidate was not emitted", ruleName, "query/output["+(i+1)+"]", null, df.sqlFragment()));
                        continue;
                    }
                    FragmentNode child=buildDecomposedNode(df,parentId,ruleName,String.valueOf(i),dialect);
                    if(child.id().equals(parentId)) { issues.add(new Issue("NO_EFFECT","Output has the source identity",ruleName,"query",parentId,df.sqlFragment()));continue; }
                    Effect effect=ExperimentEffects.describe(original,target,sourceSql,df.sqlFragment(),resolver.getProfile(dialect),df.effect());
                    children.add(child); outputs.add(new Output(parentId,ruleName,child.id(),i+1,effect));
                    nestedOutputs(child,outputs,issues,dialect);
                } catch(Exception e) { issues.add(new Issue("RULE_FAILED",degradationMarker(e),ruleName,"query/output["+(i+1)+"]",null,df.sqlFragment())); }
            }
            return new Batch(issues.isEmpty()?(children.isEmpty()?"NOT_APPLICABLE":"COMPLETE"):"PARTIAL",children,outputs,issues);
        } catch(Exception e) {
            return new Batch("FAILED",List.of(),List.of(),List.of(new Issue("RULE_FAILED",degradationMarker(e),ruleName,"query",parentId,null)));
        }
    }
    private boolean hasOffsetWithoutLimit(SqlNode node) {
        if (node instanceof SqlOrderBy o && o.offset != null && o.fetch == null) return true;
        if (node instanceof org.apache.calcite.sql.SqlSelect s && s.getOffset() != null && s.getFetch() == null) return true;
        if (node instanceof org.apache.calcite.sql.SqlNodeList list) {
            for (SqlNode item : list) if (hasOffsetWithoutLimit(item)) return true;
        } else if (node instanceof org.apache.calcite.sql.SqlCall call) {
            for (SqlNode item : call.getOperandList()) if (item != null && hasOffsetWithoutLimit(item)) return true;
        }
        return false;
    }

    private void nestedOutputs(FragmentNode parent,List<Output> outputs,List<Issue> issues,String dialect) {
        for(int i=0;i<parent.children().size();i++) {
            FragmentNode child=parent.children().get(i);
            Issue issue=new Issue("TRANSFORM_UNVERIFIED","Nested rule relation requires explicit replay",child.ruleName(),"query",child.id(),null);
            outputs.add(new Output(parent.id(),child.ruleName(),child.id(),i+1,new Effect(FragmentIdentity.sha256Hex(parent.sqlFragment()),
                FragmentIdentity.sha256Hex(child.sqlFragment()),"UNKNOWN",List.of(),List.of(),List.of(issue))));
            issues.add(issue);nestedOutputs(child,outputs,issues,dialect);
        }
    }

    /**
     * Return decomposition options grouped by level (STRUCTURE / CLAUSE / PREDICATE).
     */
    public Map<DecompositionLevel, List<DecomposeOption>> getAvailableOptionsGrouped(SqlNode rootNode, String dialect) {
        Map<DecompositionLevel, List<DecomposeOption>> grouped = new LinkedHashMap<>();
        for (DecompositionLevel level : DecompositionLevel.values()) {
            grouped.put(level, new ArrayList<>());
        }
        for (DecomposeOption option : getAvailableOptions(rootNode, dialect)) {
            grouped.get(option.level()).add(option);
        }
        // Remove empty levels
        grouped.entrySet().removeIf(e -> e.getValue().isEmpty());
        return grouped;
    }

    /**
     * Apply a single decomposition rule to an AST node.
     *
     * @param sourceNodeId stable id of the source fragment (used to populate each
     *                     child node's parentIds). When null/blank, a synthetic
     *                     id is derived from the rule + sql path so the result
     *                     is still lineage-consistent for legacy callers.
     */
    public List<FragmentNode> decompose(SqlNode rootNode, String dialect, String ruleName, String sourceNodeId) {
        DecompositionRule rule = rules.stream()
            .filter(r -> r.name().equals(ruleName))
            .findFirst()
            .orElse(null);

        if (rule == null) {
            throw new IllegalArgumentException("Unknown rule: " + ruleName);
        }

        // When the caller doesn't supply a stable source id, derive a deterministic
        // synthetic one from the rule name + (root, dialect). This keeps every
        // child node's parentIds non-empty without forcing the API contract.
        final String effectiveParentId = (sourceNodeId != null && !sourceNodeId.isBlank())
            ? sourceNodeId
            : stableId("__source__", ruleName, dialect, "0");

        try {
            List<DecomposedFragment> results = rule.apply(rootNode, dialect);
            return IntStream.range(0, results.size())
                .mapToObj(i -> {
                    DecomposedFragment df = results.get(i);
                    final int currentIndex = i;
                    return buildDecomposedNode(df, effectiveParentId, ruleName, String.valueOf(currentIndex), dialect);
                })
                .toList();
        } catch (Exception e) {
            throw new IllegalStateException("Rule failed: " + ruleName, e);
        }
    }

    /**
     * Back-compat overload — synthesizes a parent id from the source SQL.
     */
    public List<FragmentNode> decompose(SqlNode rootNode, String dialect, String ruleName) {
        return decompose(rootNode, dialect, ruleName,
            stableId("__source__", ruleName, dialect, NodeToSql.toSql(rootNode, sqlToStringProfile(rootNode, dialect))));
    }

    /**
     * Full build — applies all rules at once. Kept for backward compatibility
     * with tests and for cases where batch decomposition is desired.
     */
    public FragmentNode build(SqlNode rootNode, String dialect) {
        FragmentNode root = createRootNode(rootNode, dialect);

        List<FragmentNode> children = applyAllRules(rootNode, dialect, root.id());

        // For CTE (SqlWith) queries, also apply rules to the body query
        if (rootNode instanceof SqlWith with && with.body != null) {
            List<FragmentNode> bodyChildren = applyAllRules(with.body, dialect, root.id());
            children = new ArrayList<>(children);
            children.addAll(bodyChildren);
        }

        children = classifyChildren(children);

        root = new FragmentNode(
            root.id(), root.type(), root.label(), root.sqlFragment(),
            root.executable(), root.executionHint(),
            children.size(),
            root.ruleName(),
            children, root.parentIds(), root.transform(), root.source(), root.canonicalSql(), root.editParentId(), root.createdAt(), root.executionSummary(), root.canonicalizationError()
        );

        log.info("Decomposition complete: {} fragments from {} rule(s)",
            children.size(), rules.size());

        return root;
    }

    /**
     * Wraps FragmentNode.withLineage and additionally populates
     * {@code canonicalSql} (ADR-0001), {@code createdAt} (UTC, ISO 8601), and the
     * identity-degradation marker {@code canonicalizationError}.
     *
     * <p>If canonicalization fails (e.g. unparseable SQL) or produces unsupported
     * tokens, the raw sql is used as the identity source (unchanged behaviour) and
     * the reason is recorded on {@code canonicalizationError} so downstream
     * consumers can tell a degraded identity from a clean one. Only the marker is
     * new — the identity value itself is untouched (task 4c ruling 2).
     */
    private FragmentNode withLineageAndLineageFields(
        String id,
        FragmentType type,
        String label,
        String sqlFragment,
        boolean executable,
        String executionHint,
        int nodeCount,
        String ruleName,
        List<FragmentNode> children,
        List<String> parentIds,
        TransformRecord transform,
        SourceKind source,
        String editParentId,
        String dialect
    ) {
        CanonicalizationResult canonicalization = canonicalizeWithStatus(sqlFragment, dialect);
        String createdAt = Instant.now().toString();
        return new FragmentNode(
            id, type, label, sqlFragment, executable, executionHint,
            nodeCount, ruleName, children,
            parentIds, transform, source,
            canonicalization.canonicalSql(), editParentId, createdAt, null,
            canonicalization.error()
        );
    }

    /** Canonical SQL plus the reason identity was degraded (null = clean). */
    private record CanonicalizationResult(String canonicalSql, String error) {}

    /**
     * Canonicalize and report identity degradation (spec §4.2 — degradation must be
     * observable on the returned structure, not only logged).
     *
     * <p>Two degradation sources are captured:
     * <ol>
     *   <li>parse failure — the {@link org.apache.calcite.sql.parser.SqlParseException}
     *       message (e.g. bodyless {@code WITH} from a mutually-recursive CTE group);</li>
     *   <li>dispatch fallback — {@link SqlCanonicalizer.CanonicalForm#degraded()} with the
     *       unsupported token list (elements look like {@code SqlUpdate#<digest>}).</li>
     * </ol>
     * In both cases the raw sql is returned as the identity source, matching the
     * previous {@code canonicalizeSafely} behaviour.
     */
    private CanonicalizationResult canonicalizeWithStatus(String sql, String dialect) {
        if (sql == null || sql.isBlank()) return new CanonicalizationResult(sql, null);
        try {
            SqlCanonicalizer.CanonicalForm form = SqlCanonicalizer.canonicalizeSqlWithStatus(sql, dialect);
            return new CanonicalizationResult(form.canonicalSql(), degradationMarker(form));
        } catch (Exception e) {
            log.debug("canonicalizeSqlWithStatus failed, using raw sql: {}", e.getMessage());
            return new CanonicalizationResult(sql, degradationMarker(e));
        }
    }

    /**
     * Marker for a successful canonicalization whose token stream still contains
     * unsupported ({@code UNKNOWN}) node types — i.e. identity landed on a fallback
     * token. {@code null} = clean.
     *
     * <p>Package-private so this branch is directly unit-testable: constructing a
     * {@link SqlCanonicalizer.CanonicalForm} with a non-empty unsupported list is the
     * only way to pin it without also depending on which node types the dispatcher
     * happens to miss.
     */
    static String degradationMarker(SqlCanonicalizer.CanonicalForm form) {
        return form.degraded()
            ? "unsupported node types: " + String.join(", ", form.unsupportedNodeTypes())
            : null;
    }

    /**
     * Marker for a canonicalization failure. Totality matters here: {@code catch} is
     * {@code Exception}, so a failure with a null (or blank) message must still yield a
     * non-null marker — otherwise the degradation collapses back to log-only, which is
     * exactly the hole spec §7 closes.
     */
    static String degradationMarker(Exception failure) {
        String message = failure.getMessage();
        return (message != null && !message.isBlank())
            ? message
            : failure.getClass().getSimpleName();
    }

    private String canonicalizeSafely(String sql, String dialect) {
        if (sql == null || sql.isBlank()) return sql;
        try {
            return SqlCanonicalizer.canonicalizeSql(sql, dialect);
        } catch (Exception e) {
            return sql; // withLineageAndLineageFields publishes canonicalizationError for this identity fallback
        }
    }

    /**
     * ADR-0001 — compute node identity from canonical SQL + dialect.
     * Falls back to raw SQL if canonicalization fails. Identity is intentionally
     * unaffected by the {@code canonicalizationError} marker (task 4c ruling 2).
     */
    private String computeNodeId(String sqlFragment, String dialect) {
        String canonical = canonicalizeSafely(sqlFragment, dialect);
        return FragmentIdentity.computeId(canonical, dialect);
    }

    private List<FragmentNode> applyAllRules(SqlNode rootNode, String dialect) {
        return applyAllRules(rootNode, dialect, null);
    }

    private List<FragmentNode> applyAllRules(SqlNode rootNode, String dialect, String rootNodeId) {
        List<FragmentNode> allChildren = new ArrayList<>();
        int ruleIndex = 0;
        final String effectiveParentId = (rootNodeId != null && !rootNodeId.isBlank())
            ? rootNodeId
            : stableId("__source__", "all-rules", dialect, "0");

        for (DecompositionRule rule : rules) {
            try {
                List<DecomposedFragment> results = rule.apply(rootNode, dialect);
                for (int resultIndex = 0; resultIndex < results.size(); resultIndex++) {
                    DecomposedFragment df = results.get(resultIndex);
                    final int currentResultIndex = resultIndex;
                    final int currentRuleIndex = ruleIndex;
                    allChildren.add(buildDecomposedNode(
                        df, effectiveParentId, rule.name(),
                        currentRuleIndex + "/" + currentResultIndex, dialect));
                }
            } catch (Exception e) {
                throw new IllegalStateException("Rule failed: " + rule.name(), e);
            }
            ruleIndex++;
        }

        return allChildren;
    }

    private List<FragmentNode> classifyChildren(List<FragmentNode> children) { return List.copyOf(children); }

    private String buildRootLabel(SqlNode node) {
        if (node instanceof SqlOrderBy ob && ob.query instanceof SqlSelect) {
            return "Original Query";
        }
        // Top-level ORDER BY over a WITH query → SqlOrderBy(query=SqlWith).
        // Without this branch the shape fell through to the "Original SQL"
        // fallback, mislabelling the root of a CTE query (PR-3 7c / follow-up 7).
        if (node instanceof SqlOrderBy ob && ob.query instanceof SqlWith) {
            return "Original CTE Query";
        }
        if (node instanceof SqlSelect) {
            return "Original Query";
        }
        if (node instanceof SqlWith) {
            return "Original CTE Query";
        }
        if (node instanceof SqlBasicCall call) {
            String op = call.getOperator().getName();
            return "Original " + op + " Query";
        }
        return "Original SQL";
    }

    private String sqlToString(SqlNode node, String dialect) {
        if (node == null) {
            return "";
        }
        try {
            // P0-4: store the full SQL — SQLite TEXT has no practical limit;
            // display truncation is the frontend's job.
            return NodeToSql.toSql(node, sqlToStringProfile(node, dialect));
        } catch (Exception e) {
            throw new IllegalStateException("SQL serialization failed", e);
        }
    }

    /** Resolve a DialectProfile for a dialect name via the injected resolver. */
    private DialectProfile sqlToStringProfile(SqlNode ignored, String dialect) {
        return resolver.getProfile(dialect);
    }

    private String stableId(String type, String label, String sqlFragment, String parentPath) {
        String input = type + "|" + label + "|" + sqlFragment + "|" + parentPath;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-1 not available", e);
        }
    }

    private FragmentNode buildChildFragmentNode(DecomposedFragment c, String parentPath, int childIndex, String dialect) {
        // Children of a decomposed fragment inherit the parent's parentIds
        // (we don't have a real parent id at this depth — the engine builds
        // a flat list under the rule's primary fragment, and the rule's
        // primary fragment is the single source of truth in the DAG).
        return withLineageAndLineageFields(
            computeNodeId(c.sqlFragment(), dialect),
            c.type(),
            c.label(),
            c.sqlFragment(),
            c.executable(),
            c.executionHint(),
            0,
            c.ruleName(),
            List.of(),
            List.of(),
            new TransformRecord(TransformRecord.TransformKind.DECOMPOSE, c.ruleName(), c.label(), null),
            SourceKind.AUTO,
            null,
            dialect
        );
    }

    /**
     * Build a top-level fragment node produced by a decomposition rule, with
     * full lineage information so the DAG layer can reverse-traverse it.
     *
     * @param df        the decomposed fragment
     * @param parentId  stable id of the source fragment (its direct ancestor)
     * @param ruleName  name of the rule that produced this fragment
     * @param pathKey   index/path used to keep stableId unique among siblings
     */
    private FragmentNode buildDecomposedNode(DecomposedFragment df, String parentId, String ruleName, String pathKey, String dialect) {
        String childParentId = computeNodeId(df.sqlFragment(), dialect);
        List<FragmentNode> children = IntStream.range(0, df.children().size())
            .mapToObj(ci -> buildDecomposedNode(df.children().get(ci), childParentId, df.children().get(ci).ruleName(), pathKey + "/" + ci, dialect))
            .toList();
        String id = computeNodeId(df.sqlFragment(), dialect);
        return withLineageAndLineageFields(
            id,
            df.type(),
            df.label(),
            df.sqlFragment(),
            df.executable(),
            df.executionHint(),
            0,
            df.ruleName(),
            children,
            List.of(parentId),                                                       // parentIds — the DAG edge
            new TransformRecord(
                TransformRecord.TransformKind.DECOMPOSE,
                ruleName,
                "Applied " + ruleName,
                null),
            SourceKind.AUTO,
            null,
            dialect
        );
    }
}
