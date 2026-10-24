package com.bitdata.sqlworkbench.sql;

import com.bitdata.sqlworkbench.fragment.FragmentIdentity;
import com.bitdata.sqlworkbench.sql.decompose.AggregateInfoExtractor;
import com.bitdata.sqlworkbench.sql.decompose.CteDependencyGraph;
import com.bitdata.sqlworkbench.sql.decompose.JoinInfoExtractor;
import com.bitdata.sqlworkbench.sql.decompose.WhereInfoExtractor;
import com.bitdata.sqlworkbench.sql.model.*;

import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.parser.SqlParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class SqlParseService {

    private static final Logger log = LoggerFactory.getLogger(SqlParseService.class);
    private static final int MAX_SQL_LENGTH = 100_000;
    private static final int MAX_CACHE_SIZE = 10;

    private final SqlParseEngine parseEngine;
    private final DecompositionService decompositionService;
    private final SqlDialectResolver resolver;
    private final ConnectedColumnResolver columns;

    private final Map<String, ParseResult> cache = Collections.synchronizedMap(
        new LinkedHashMap<>() {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, ParseResult> eldest) {
                return size() > MAX_CACHE_SIZE;
            }
        }
    );

    public SqlParseService(SqlParseEngine parseEngine, DecompositionService decompositionService, SqlDialectResolver resolver) {
        this(parseEngine, decompositionService, resolver, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public SqlParseService(SqlParseEngine parseEngine, DecompositionService decompositionService, SqlDialectResolver resolver, ConnectedColumnResolver columns) {
        this.parseEngine = parseEngine;
        this.decompositionService = decompositionService;
        this.resolver = resolver;
        this.columns = columns;
    }

    /**
     * Parse SQL and return the root FragmentNode with available decomposition options.
     * No decomposition is applied automatically — the user chooses which rule to apply.
     */
    public ParseResult parse(ParseRequest request) {
        validate(request);

        String cacheKey = cacheKey(request.sql(), request.dialect());
        boolean connected = request.connectionId() != null && !request.connectionId().isBlank();
        ParseResult cached = connected ? null : cache.get(cacheKey);
        if (cached != null) {
            log.debug("Cache hit for SQL parse");
            return cached;
        }

        try {
            SqlNode sqlNode = parseEngine.parse(request.sql(), request.dialect());
            FragmentNode root = decompositionService.createRootNode(sqlNode, request.dialect());
            DecompositionResult.Options options = scanResolved(sqlNode, request.dialect(), request.connectionId());

            ParseResult result = new ParseResult(root, options.options(), Collections.emptyList(), options.scan());
            if (!connected && "COMPLETE".equals(options.scan().status())) cache.put(cacheKey, result);
            return result;
        } catch (SqlParseException e) {
            log.warn("SQL parse error: {}", e.getMessage());
            if (e.getPos() != null) {
                log.warn("  at line {}, column {}: token '{}'",
                    e.getPos().getLineNum(), e.getPos().getColumnNum(), extractToken(e.getMessage()));
            }
            return new ParseResult(
                null,
                Collections.emptyList(),
                Collections.singletonList(mapParseError(e))
            );
        }
    }

    /**
     * Apply a single decomposition rule to a SQL fragment.
     * Parses the fragment, applies the rule, and returns child FragmentNodes.
     */
    public List<FragmentNode> decompose(String sqlFragment, String dialect, String ruleName)
        throws SqlParseException {
        return decompose(sqlFragment, dialect, ruleName, null);
    }

    public List<FragmentNode> decompose(String sqlFragment, String dialect, String ruleName, String sourceNodeId)
        throws SqlParseException {
        SqlNode sqlNode = parseEngine.parse(sqlFragment, dialect);
        return decompositionService.decompose(sqlNode, dialect, ruleName, sourceNodeId);
    }

    /**
     * Get available decomposition options for a SQL fragment.
     */
    public List<DecomposeOption> getDecomposeOptions(String sqlFragment, String dialect)
        throws SqlParseException {
        SqlNode sqlNode = parseEngine.parse(sqlFragment, dialect);
        return decompositionService.getAvailableOptions(sqlNode, dialect);
    }

    public DecompositionResult.Batch decomposeDetailed(String sql,String dialect,String rule,String sourceId) throws SqlParseException {
        return decomposeDetailed(sql,dialect,rule,sourceId,null);
    }
    public DecompositionResult.Options scanOptions(String sql,String dialect) throws SqlParseException {
        return scanOptions(sql,dialect,null);
    }

    public DecompositionResult.Batch decomposeDetailed(String sql,String dialect,String rule,String sourceId,String connectionId) throws SqlParseException {
        SqlNode original = parseEngine.parse(sql,dialect);
        var resolved = resolve(original,dialect,connectionId);
        var batch = decompositionService.decomposeDetailed(resolved.node(),original,sql,dialect,rule,sourceId);
        if (resolved.issues().isEmpty()) return batch;
        var issues = new java.util.ArrayList<>(batch.issues()); issues.addAll(resolved.issues());
        return new DecompositionResult.Batch("FAILED".equals(batch.status())?"FAILED":"PARTIAL",batch.children(),batch.outputs(),issues);
    }
    public DecompositionResult.Options scanOptions(String sql,String dialect,String connectionId) throws SqlParseException {
        return scanResolved(parseEngine.parse(sql,dialect),dialect,connectionId);
    }
    private ConnectedColumnResolver.Resolution resolve(SqlNode node,String dialect,String connectionId) {
        return columns == null ? new ConnectedColumnResolver.Resolution(node,List.of()) : columns.resolve(node,dialect,connectionId);
    }
    private DecompositionResult.Options scanResolved(SqlNode node,String dialect,String connectionId) {
        var resolved = resolve(node,dialect,connectionId);
        var options = decompositionService.scanOptions(resolved.node(),dialect);
        if (resolved.issues().isEmpty()) return options;
        var issues = new java.util.ArrayList<>(options.scan().issues()); issues.addAll(resolved.issues());
        return new DecompositionResult.Options(options.options(),new DecompositionResult.Scan(
            "FAILED".equals(options.scan().status())?"FAILED":"PARTIAL",issues));
    }

    /**
     * Compute the set of JOINs present in the parent fragment but absent
     * from the child fragment. Used by the "Add JOIN" composition feature.
     *
     * <p>Both fragments are parsed (and silently tolerate parse failure —
     * empty SQL or invalid input returns an empty diff rather than 500).
     */
    public JoinDiffResponse joinDiff(JoinDiffRequest request) {
        if (request.parentSql() == null || request.parentSql().isBlank()
            || request.childSql() == null || request.childSql().isBlank()) {
            return new JoinDiffResponse(List.of());
        }
        String dialect = request.dialect() != null ? request.dialect() : "postgresql";
        try {
            SqlNode parentNode = parseEngine.parse(request.parentSql(), dialect);
            SqlNode childNode = parseEngine.parse(request.childSql(), dialect);
            DialectProfile profile = resolver.getProfile(dialect);
            List<JoinInfo> parentJoins = JoinInfoExtractor.extractJoins(parentNode, profile);
            List<JoinInfo> childJoins = JoinInfoExtractor.extractJoins(childNode, profile);
            List<JoinInfo> diff = JoinInfoExtractor.diffJoins(parentJoins, childJoins);
            List<JoinDiffResponse.JoinDiffEntry> entries = diff.stream()
                .map(j -> new JoinDiffResponse.JoinDiffEntry(
                    j.tableName(),
                    j.tableAlias(),
                    j.joinType().name(),
                    j.onConditionSql(),
                    j.position()))
                .toList();
            return new JoinDiffResponse(entries);
        } catch (Exception e) {
            return new JoinDiffResponse(List.of(),true,e.getMessage()==null?e.getClass().getSimpleName():e.getMessage());
        }
    }

    /**
     * Generic fragment-diff for Phase 2 composition features. Dispatches
     * by {@code type} to the corresponding extractor:
     * <ul>
     *   <li>"join" → JoinInfoExtractor</li>
     *   <li>"where" → WhereInfoExtractor</li>
     *   <li>"aggregate" → AggregateInfoExtractor</li>
     * </ul>
     *
     * <p>Only the list corresponding to the requested type is populated;
     * the other two are null. This keeps the wire format simple and lets
     * the frontend cache the response by (parentId, childId, type).
     */
    public FragmentDiffRequest.FragmentDiffResponse fragmentDiff(FragmentDiffRequest request) {
        if (request.parentSql() == null || request.parentSql().isBlank()
            || request.childSql() == null || request.childSql().isBlank()) {
            return new FragmentDiffRequest.FragmentDiffResponse(null, null, null);
        }
        String dialect = request.dialect() != null ? request.dialect() : "postgresql";
        String type = request.type() != null ? request.type() : FragmentDiffRequest.TYPE_JOIN;
        try {
            SqlNode parentNode = parseEngine.parse(request.parentSql(), dialect);
            SqlNode childNode = parseEngine.parse(request.childSql(), dialect);
            DialectProfile profile = resolver.getProfile(dialect);

            return switch (type) {
                case FragmentDiffRequest.TYPE_JOIN -> {
                    List<JoinInfo> diff = JoinInfoExtractor.diffJoins(
                        JoinInfoExtractor.extractJoins(parentNode, profile),
                        JoinInfoExtractor.extractJoins(childNode, profile));
                    List<FragmentDiffRequest.JoinEntry> joins = diff.stream()
                        .map(j -> new FragmentDiffRequest.JoinEntry(
                            j.tableName(), j.tableAlias(), j.joinType().name(),
                            j.onConditionSql(), j.position()))
                        .toList();
                    yield new FragmentDiffRequest.FragmentDiffResponse(joins, null, null);
                }
                case FragmentDiffRequest.TYPE_WHERE -> {
                    List<WhereInfo> diff = WhereInfoExtractor.diffWhereConditions(
                        WhereInfoExtractor.extractWhereConditions(parentNode, profile),
                        WhereInfoExtractor.extractWhereConditions(childNode, profile));
                    List<FragmentDiffRequest.WhereEntry> where = diff.stream()
                        .map(w -> new FragmentDiffRequest.WhereEntry(
                            w.conditionSql(), w.position()))
                        .toList();
                    yield new FragmentDiffRequest.FragmentDiffResponse(null, where, null);
                }
                case FragmentDiffRequest.TYPE_AGGREGATE -> {
                    List<AggregateInfo> diff = AggregateInfoExtractor.diffAggregates(
                        AggregateInfoExtractor.extractAggregates(parentNode, profile),
                        AggregateInfoExtractor.extractAggregates(childNode, profile));
                    List<FragmentDiffRequest.AggregateEntry> aggregates = diff.stream()
                        .map(a -> new FragmentDiffRequest.AggregateEntry(
                            a.kind(), a.expressionSql(), a.position()))
                        .toList();
                    yield new FragmentDiffRequest.FragmentDiffResponse(null, null, aggregates);
                }
                default -> new FragmentDiffRequest.FragmentDiffResponse(null, null, null);
            };
        } catch (Exception e) {
            return new FragmentDiffRequest.FragmentDiffResponse(null,null,null,true,e.getMessage()==null?e.getClass().getSimpleName():e.getMessage());
        }
    }

    /**
     * Get CTE dependency information for a SQL query.
     * Returns the dependency graph for each CTE and which CTEs the body depends on.
     */
    public CteDependencyResult getCteDependencies(String sql, String dialect)
        throws SqlParseException {
        SqlNode sqlNode = parseEngine.parse(sql, dialect);
        if (!(sqlNode instanceof org.apache.calcite.sql.SqlWith with)) {
            return new CteDependencyResult(List.of(), List.of());
        }

        CteDependencyGraph graph = CteDependencyGraph.build(with, dialect);

        List<CteDependencyResult.CteInfo> ctes = graph.allCteNames().stream()
            .map(name -> {
                java.util.Set<String> deps = graph.transitiveDependencies(name);
                return new CteDependencyResult.CteInfo(name, deps.isEmpty() ? List.of() : deps.stream().toList());
            })
            .toList();

        // Body depends on all CTEs referenced in it
        java.util.Set<String> bodyDeps = CteDependencyGraph.collectCteRefs(
            with.body, graph.allCteNames()
        );
        return new CteDependencyResult(ctes, bodyDeps.isEmpty() ? List.of() : bodyDeps.stream().toList());
    }

    private void validate(ParseRequest request) {
        if (request.sql() == null || request.sql().isBlank()) {
            throw new IllegalArgumentException("SQL text is required");
        }
        if (request.sql().length() > MAX_SQL_LENGTH) {
            throw new IllegalArgumentException("SQL exceeds maximum length of " + MAX_SQL_LENGTH + " characters");
        }
        String dialect = request.dialect();
        if (!resolver.isSupported(dialect)) {
            throw new IllegalArgumentException("Unsupported dialect: " + dialect);
        }
    }

    private ParseResult.ParseError mapParseError(SqlParseException e) {
        String message = e.getMessage();
        ParseResult.ParseErrorDetail detail = null;

        if (e.getPos() != null) {
            int line = e.getPos().getLineNum();
            int col = e.getPos().getColumnNum();
            detail = new ParseResult.ParseErrorDetail(
                new ParseResult.ParsePosition(line, col),
                extractToken(message),
                suggestFix(message)
            );
        }

        return new ParseResult.ParseError("SQL_PARSE_ERROR", message, detail);
    }

    private String extractToken(String message) {
        int encIdx = message.indexOf("Encountered");
        if (encIdx >= 0) {
            int start = message.indexOf("\"", encIdx);
            int end = message.indexOf("\"", start + 1);
            if (start >= 0 && end > start) {
                return message.substring(start + 1, end);
            }
        }
        return null;
    }

    private String suggestFix(String message) {
        if (message.contains("LIMIT")) {
            return "Use LIMIT count OFFSET offset instead of LIMIT offset, count";
        }
        if (message.contains("Encountered")) {
            return "Check syntax near the highlighted position";
        }
        return null;
    }

    private String cacheKey(String sql, String dialect) {
        // P0-5: 32-bit hashCode collides (e.g. "Aa"/"BB") and would serve one
        // SQL's ParseResult for another. SHA-256, same utility as FragmentIdentity.
        return (dialect != null ? dialect.toLowerCase() : "postgresql") + ":" + FragmentIdentity.sha256Hex(sql);
    }

    public void clearCache() {
        cache.clear();
    }
}
