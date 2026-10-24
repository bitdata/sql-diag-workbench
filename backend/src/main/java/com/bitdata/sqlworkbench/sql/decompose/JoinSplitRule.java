package com.bitdata.sqlworkbench.sql.decompose;

import com.bitdata.sqlworkbench.sql.DialectProfile;
import com.bitdata.sqlworkbench.sql.NodeToSql;
import com.bitdata.sqlworkbench.sql.SqlDialectResolver;
import com.bitdata.sqlworkbench.sql.model.FragmentType;
import com.bitdata.sqlworkbench.sql.model.DecompositionResult.*;

import org.apache.calcite.sql.*;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.sql.parser.SqlParserPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Splits multi-table JOIN queries using AST-level decomposition.
 *
 * <p>Instead of building SQL via string concatenation (which loses JOIN structure),
 * this rule manipulates the Calcite AST to remove one table at a time while
 * preserving the original JOIN types, ON conditions, and clause structure.
 *
 * <p>For a chain A INNER JOIN B INNER JOIN C:
 * - Remove C → query with A JOIN B, columns from A and B, conditions from A and B
 * - Remove B → query with A JOIN C, columns from A and C, conditions from A and C
 * - Remove A → query with B JOIN C, columns from B and C, conditions from B and C
 *
 * <p>Bridge table detection: if removing a table disconnects the join
 * graph (no remaining ON condition connects the two sides), that fragment
 * is skipped because it produces a meaningless Cartesian product.
 */
@Component
public class JoinSplitRule implements DecompositionRule {

    private static final Logger log = LoggerFactory.getLogger(JoinSplitRule.class);
    private static final SqlParserPos POS = SqlParserPos.ZERO;

    private final SqlDialectResolver resolver;

    public JoinSplitRule(SqlDialectResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    public String name() {
        return "join-split";
    }

    @Override
    public String displayName() {
        return "Split JOIN";
    }

    @Override
    public DecompositionLevel level() {
        return DecompositionLevel.STRUCTURE;
    }

    @Override
    public String description() {
        return "Remove one table from a JOIN to see which columns and conditions disappear with it";
    }

    @Override
    public List<DecomposedFragment> apply(SqlNode root, String dialect) {
        RuleEvaluation result=evaluate(root,dialect);
        if(!result.issues().isEmpty())throw new IllegalStateException(result.issues().toString());
        return result.fragments();
    }

    @Override
    public RuleEvaluation evaluate(SqlNode root,String dialect) {
        JoinCandidateSafety safety=new JoinCandidateSafety(dialect);
        SqlNode target=root;
        while(target instanceof SqlOrderBy || target instanceof SqlWith) {
            target=target instanceof SqlOrderBy ob?ob.query:((SqlWith)target).body;
        }

        if (!(target instanceof SqlSelect select)) {
            return new RuleEvaluation(List.of(),List.of());
        }

        SqlNode fromNode = select.getFrom();
        if (!(fromNode instanceof SqlJoin join)) {
            return new RuleEvaluation(List.of(),List.of());
        }

        List<LeafRelation> leaves = new ArrayList<>();
        collectLeaves(safety, join, leaves);

        if (leaves.size() <= 1) {
            return new RuleEvaluation(List.of(),List.of());
        }

        DialectProfile profile = getProfile(dialect);

        // Build the set of all leaf aliases for column/condition filtering
        Set<String> allAliases = leaves.stream()
            .map(lr -> lr.alias)
            .collect(Collectors.toCollection(LinkedHashSet::new));

        // Build connectivity graph from ON conditions (for bridge table detection)
        Map<String, Set<String>> connectivity = buildConnectivityGraph(safety, join, leaves);

        List<DecomposedFragment> fragments = new ArrayList<>();
        List<Issue> issues=new ArrayList<>();
        for (int i = 0; i < leaves.size(); i++) {
            String removedName = leaves.get(i).name;
            String removedAlias = leaves.get(i).alias;
            Set<String> remainingAliases = new LinkedHashSet<>();
            for (int j = 0; j < leaves.size(); j++) {
                if (j != i) remainingAliases.add(leaves.get(j).alias);
            }

            // Bridge table detection: skip if removing this table disconnects the graph
            if (!isConnectedAfterRemoval(leaves, connectivity, i)) {
                log.debug("Skipping fragment for {} — bridge table would disconnect remaining tables", removedName);
                continue;
            }

            // Rebuild the JOIN tree without the removed leaf
            LeafCounter counter = new LeafCounter();
            SqlNode newFrom = removeLeafFromTree(safety, fromNode, i, counter, remainingAliases, allAliases);
            if (newFrom == null) continue;

            // Prune SELECT list
            SqlNodeList newSelectList = pruneSelectList(safety, select.getSelectList(), remainingAliases, allAliases, removedName, profile);
            List<String> prunedColumns = new ArrayList<>();

            // Supplement with JOIN key columns from ON conditions (before star fallback)
            SqlNodeList newGroup=filterClauseList(safety, select.getGroup(),remainingAliases,allAliases);
            if(newGroup==null&&!JoinCandidateSafety.aggregate(newSelectList)&&!JoinCandidateSafety.aggregate(select.getHaving()))
                supplementJoinKeyColumns(safety, newSelectList, join, removedAlias, remainingAliases);

            if (newSelectList == null || newSelectList.isEmpty()) {
                // Everything was pruned and no join keys supplemented — use *
                newSelectList = new SqlNodeList(POS);
                newSelectList.add(SqlIdentifier.star(POS));
            } else {
                // Count how many original columns were pruned (not supplemented)
                Set<String> origColNames = new LinkedHashSet<>();
                for (SqlNode item : select.getSelectList()) {
                    if (item instanceof SqlIdentifier id && !id.isStar()) {
                        origColNames.add(id.toString().toUpperCase());
                    }
                }
                Set<String> newColNames = new LinkedHashSet<>();
                for (SqlNode item : newSelectList) {
                    if (item instanceof SqlIdentifier id && !id.isStar()) {
                        newColNames.add(id.toString().toUpperCase());
                    }
                }
                int prunedCount = (int) origColNames.stream().filter(c -> !newColNames.contains(c)).count();
                if (prunedCount > 0) {
                    prunedColumns.add(String.valueOf(prunedCount));
                }
            }

            // Filter WHERE clause
            SqlNode newWhere = filterWhereByAliases(safety, select.getWhere(), remainingAliases, allAliases);

            // Build the new SqlSelect
            SqlSelect newSelect = new SqlSelect(
                POS,
                (SqlNodeList) select.getOperandList().get(0),  // P0-3 #1: preserve DISTINCT/ALL
                newSelectList,
                newFrom,
                newWhere,
                newGroup,
                select.getHaving(),
                select.getWindowList(),
                filterClauseList(safety, select.getOrderList(),remainingAliases,allAliases),
                select.getOffset(),
                select.getFetch(),
                select.getHints()
            );
            newSelect.setQualify(select.getQualify());

            SqlNode resultNode=rebuildEnvelope(root,newSelect,safety,remainingAliases,allAliases);
            String sql = NodeToSql.toSql(resultNode, profile);
            String problem=safety.problem(select,newSelect,null,allAliases,remainingAliases);
            for(SqlNodeList order:envelopeOrders(root)) {
                String suffixProblem=safety.problem(select,newSelect,filterClauseList(safety,order,remainingAliases,allAliases),allAliases,remainingAliases);
                if(suffixProblem!=null)problem=suffixProblem;
            }
            String scope="query/from/leaf["+i+"]";
            if(problem!=null){issues.add(new Issue("UNRESOLVED_DEPENDENCY",problem,name(),scope,null,sql));continue;}
            log.debug("JOIN_SPLIT fragment {}: removing={}, SQL={}", i, removedName, sql);

            // Build label
            String label = "Remove " + removedName;
            FragmentType type = FragmentType.JOIN_SPLIT;
            if (!prunedColumns.isEmpty()) {
                type = FragmentType.COLUMN_PRUNED;
                label = "Remove " + removedName + " — pruned " + prunedColumns.get(0) + " column(s)";
            }

            fragments.add(new DecomposedFragment(
                type,
                label,
                sql,
                name(),
                true,
                "Removed " + removedName + " — " + remainingAliases.size() + " table(s) remain",
                List.of(),
                new Effect(null,null,"PRESERVED",List.of(new Change("REMOVE","JOIN_RELATION",scope,removedName,null)),List.of(),List.of())
            ));
        }

        return new RuleEvaluation(fragments,issues);
    }

    private SqlNode rebuildEnvelope(SqlNode original,SqlSelect replacement,JoinCandidateSafety safety,Set<String> remaining,Set<String> all) {
        if(original instanceof SqlWith with)return new SqlWith(with.getParserPosition(),with.withList,rebuildEnvelope(with.body,replacement,safety,remaining,all));
        if(original instanceof SqlOrderBy order) {
            SqlNode query=rebuildEnvelope(order.query,replacement,safety,remaining,all);
            SqlNodeList list=filterClauseList(safety,order.orderList,remaining,all);
            return list==null&&order.offset==null&&order.fetch==null?query:new SqlOrderBy(order.getParserPosition(),query,list==null?SqlNodeList.EMPTY:list,order.offset,order.fetch);
        }
        return replacement;
    }
    private List<SqlNodeList> envelopeOrders(SqlNode root) {
        List<SqlNodeList> result=new ArrayList<>();
        while(root instanceof SqlWith || root instanceof SqlOrderBy) {
            if(root instanceof SqlOrderBy order){result.add(order.orderList);root=order.query;}
            else root=((SqlWith)root).body;
        }
        return result;
    }

    // ── Leaf collection ────────────────────────────────────────────────────────

    private void collectLeaves(JoinCandidateSafety safety, SqlJoin join, List<LeafRelation> result) {
        JoinType type = joinTypeLabel(join);

        if (join.getLeft() instanceof SqlJoin leftJoin) {
            collectLeaves(safety, leftJoin, result);
        } else {
            result.add(new LeafRelation(join.getLeft(), type, relationName(join.getLeft()), extractAlias(safety, join.getLeft())));
        }

        if (join.getRight() instanceof SqlJoin rightJoin) {
            collectLeaves(safety, rightJoin, result);
        } else {
            result.add(new LeafRelation(join.getRight(), join.getJoinType(), relationName(join.getRight()), extractAlias(safety, join.getRight())));
        }
    }

    // ── AST-based JOIN tree manipulation ────────────────────────────────────────

    /**
     * Removes the leaf at {@code targetIndex} from the JOIN tree.
     * When a leaf is found, returns its sibling (promoting it up the tree).
     * The ON condition of the removed join is discarded. ON conditions of
     * surviving joins are cleaned of conjuncts referencing the removed
     * leaf's alias (P0-3 #4), via {@code remainingAliases}/{@code allAliases}.
     *
     * @return the rebuilt tree, or null if the tree becomes a single removed leaf
     */
    private SqlNode removeLeafFromTree(JoinCandidateSafety safety, SqlNode node, int targetIndex, LeafCounter counter,
                                       Set<String> remainingAliases, Set<String> allAliases) {
        if (!(node instanceof SqlJoin join)) {
            // This is a leaf node
            if (counter.current == targetIndex) {
                counter.current++;
                return null; // Signal: this leaf should be removed
            }
            counter.current++;
            return node;
        }

        SqlNode newLeft = removeLeafFromTree(safety, join.getLeft(), targetIndex, counter, remainingAliases, allAliases);
        SqlNode newRight = removeLeafFromTree(safety, join.getRight(), targetIndex, counter, remainingAliases, allAliases);

        // If left was removed, return right (promoted)
        if (newLeft == null) return newRight;
        // If right was removed, return left (promoted)
        if (newRight == null) return newLeft;

        // Both sides survive — rebuild the join
        // P0-3 #4: clean the surviving join's ON condition the same way
        // WHERE is cleaned — drop conjuncts that reference the removed leaf.
        SqlNode cleanedCondition = join.getCondition() == null
            ? null
            : filterWhereByAliases(safety, join.getCondition(), remainingAliases, allAliases);
        if (join.getCondition() != null && cleanedCondition == null) {
            log.warn("join-split: ON condition of a surviving join referenced only the removed"
                + " table; fragment degrades to a cross join");
        }
        return new SqlJoin(POS, newLeft,
            SqlLiteral.createBoolean(join.isNatural(), POS),
            join.getJoinTypeNode(), newRight, join.getConditionTypeNode(),
            cleanedCondition);
    }

    // ── Connectivity / bridge detection ─────────────────────────────────────────

    /**
     * Builds an adjacency map: alias → set of aliases connected via ON conditions.
     */
    private Map<String, Set<String>> buildConnectivityGraph(JoinCandidateSafety safety, SqlJoin join, List<LeafRelation> leaves) {
        // Map alias to leaf index for quick lookup
        Map<String, Integer> aliasToIndex = new HashMap<>();
        for (int i = 0; i < leaves.size(); i++) {
            aliasToIndex.put(leaves.get(i).alias, i);
        }

        Map<String, Set<String>> graph = new LinkedHashMap<>();
        for (LeafRelation lr : leaves) {
            graph.put(lr.alias, new LinkedHashSet<>());
        }

        // Walk the join tree and add edges for each ON condition
        addEdges(safety, join, graph, aliasToIndex);

        return graph;
    }

    private void addEdges(JoinCandidateSafety safety, SqlNode node, Map<String, Set<String>> graph, Map<String, Integer> aliasToIndex) {
        if (!(node instanceof SqlJoin join)) return;

        if (join.getConditionType() == JoinConditionType.ON && join.getCondition() != null) {
            // Collect aliases referenced in the ON condition
            Set<String> referencedAliases = new LinkedHashSet<>();
            collectAliasRefs(safety, join.getCondition(), referencedAliases, aliasToIndex.keySet());

            // Add edges between all pairs of referenced aliases
            List<String> refs = new ArrayList<>(referencedAliases);
            for (int i = 0; i < refs.size(); i++) {
                for (int j = i + 1; j < refs.size(); j++) {
                    graph.get(refs.get(i)).add(refs.get(j));
                    graph.get(refs.get(j)).add(refs.get(i));
                }
            }
        }

        addEdges(safety, join.getLeft(), graph, aliasToIndex);
        addEdges(safety, join.getRight(), graph, aliasToIndex);
    }

    /**
     * Checks if the remaining leaves (after removing leaf at removedIndex)
     * are still connected in the ON-condition graph.
     * If there are no ON conditions at all (comma join), all fragments are valid.
     */
    private boolean isConnectedAfterRemoval(List<LeafRelation> leaves,
                                             Map<String, Set<String>> graph,
                                             int removedIndex) {
        if (leaves.size() <= 2) return true; // Single remaining table is trivially connected

        // If there are no ON conditions at all (pure comma join), skip detection
        boolean hasAnyEdges = graph.values().stream().anyMatch(s -> !s.isEmpty());
        if (!hasAnyEdges) return true;

        Set<String> remaining = new LinkedHashSet<>();
        for (int j = 0; j < leaves.size(); j++) {
            if (j != removedIndex) {
                remaining.add(leaves.get(j).alias);
            }
        }

        // BFS from any remaining node
        String start = remaining.iterator().next();
        Set<String> visited = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(start);
        visited.add(start);

        while (!queue.isEmpty()) {
            String current = queue.poll();
            for (String neighbor : graph.getOrDefault(current, Set.of())) {
                if (remaining.contains(neighbor) && !visited.contains(neighbor)) {
                    visited.add(neighbor);
                    queue.add(neighbor);
                }
            }
        }

        return visited.size() == remaining.size();
    }

    // ── AST-based WHERE filtering ──────────────────────────────────────────────

    /**
     * Removes conditions from the WHERE clause that reference removed aliases.
     * Works on the Calcite AST: flattens top-level AND, filters individual
     * conditions, then rebuilds the AND tree.
     *
     * @param where The WHERE clause to filter
     * @param remainingAliases Aliases of tables that remain after removal
     * @param allAliases All table aliases (remaining + removed) for reference detection
     */
    private SqlNode filterWhereByAliases(JoinCandidateSafety safety, SqlNode where, Set<String> remainingAliases, Set<String> allAliases) {
        if (where == null) return null;

        List<SqlNode> andConditions = new ArrayList<>();
        flattenAndRecursive(where, andConditions);

        List<SqlNode> kept = new ArrayList<>();
        for (SqlNode cond : andConditions) {
            Set<String> refs = new LinkedHashSet<>();
            collectAliasRefs(safety, cond, refs, allAliases);  // Use allAliases to detect ALL references
            // Keep condition only if all referenced aliases are in remainingAliases
            if (refs.stream().allMatch(a -> remainingAliases.contains(a))) {
                kept.add(cond);
            }
        }

        return rebuildAndTree(kept);
    }

    /**
     * P0-3 #3: drop clause-list items (GROUP BY / ORDER BY entries) that
     * reference a removed alias. Unqualified items have no provable owner —
     * keep them (conservative, same boundary as #5).
     */
    private SqlNodeList filterClauseList(JoinCandidateSafety safety, SqlNodeList list, Set<String> remainingAliases, Set<String> allAliases) {
        if (list == null) {
            return null;
        }
        SqlNodeList filtered = new SqlNodeList(list.getParserPosition());
        for (SqlNode item : list) {
            Set<String> refs = new HashSet<>();
            collectAliasRefs(safety, item, refs, allAliases);
            boolean referencesRemoved = refs.stream()
                .anyMatch(a -> !remainingAliases.contains(a));
            if (!referencesRemoved) {
                filtered.add(item);
            }
        }
        // An empty (non-null) list would unparse as a dangling GROUP BY keyword —
        // SqlSelectOperator.unparse emits GROUP BY whenever groupBy != null and the
        // SqlNodeList.EMPTY singleton does NOT suppress it (unlike SqlOrderBy, which
        // skips the keyword only for EMPTY). Drop the whole clause instead.
        if (filtered.isEmpty()) {
            return null;
        }
        return filtered;
    }

    /**
     * Flattens an AND expression tree into individual predicates.
     */
    private static void flattenAndRecursive(SqlNode node, List<SqlNode> result) {
        if (node instanceof SqlBasicCall call
            && call.getOperator() == SqlStdOperatorTable.AND) {
            for (SqlNode operand : call.getOperandList()) {
                flattenAndRecursive(operand, result);
            }
        } else {
            result.add(node);
        }
    }

    /**
     * Rebuilds an AND tree from a list of conditions.
     * Returns null if empty, the sole condition if size=1, or a left-deep AND tree.
     */
    static SqlNode rebuildAndTree(List<SqlNode> conditions) {
        if (conditions.isEmpty()) return null;
        if (conditions.size() == 1) return conditions.get(0);
        // Left-deep: ((c1 AND c2) AND c3)
        SqlBasicCall tree = new SqlBasicCall(
            SqlStdOperatorTable.AND,
            new SqlNode[]{conditions.get(0), conditions.get(1)},
            POS
        );
        for (int i = 2; i < conditions.size(); i++) {
            tree = new SqlBasicCall(
                SqlStdOperatorTable.AND,
                new SqlNode[]{tree, conditions.get(i)},
                POS
            );
        }
        return tree;
    }

    // ── AST-based SELECT list pruning ─────────────────────────────────────────

    /**
     * Removes SELECT list items that reference removed table aliases.
     * Returns the pruned list, or null if no pruning was needed.
     *
     * @param selectList The SELECT list to prune
     * @param remainingAliases Aliases of tables that remain after removal
     * @param allAliases All table aliases (remaining + removed) for reference detection
     * @param dialect SQL dialect for output formatting
     */
    private SqlNodeList pruneSelectList(JoinCandidateSafety safety, SqlNodeList selectList, Set<String> remainingAliases,
                                         Set<String> allAliases, String removedName,
                                         DialectProfile profile) {
        boolean changed = false;
        SqlNodeList newList = new SqlNodeList(POS);

        for (SqlNode item : selectList) {
            Set<String> refs = new LinkedHashSet<>();
            collectAliasRefs(safety, item, refs, allAliases);  // Use allAliases to detect ALL references
            if (refs.isEmpty()) {
                // P0-3 #5: unqualified item — ownership of the removed table
                // cannot be proven without a schema catalog. Conservative rule
                // (宁可保留也不误删): keep it, but make it visible.
                if (isBareColumnReference(item)) {
                    log.warn("join-split: keeping unqualified column '{}' in fragment removing {}"
                        + " — ownership not provable", item, removedName);
                }
                newList.add(item);
            } else if (refs.stream().allMatch(a -> remainingAliases.contains(a))) {
                // All referenced aliases are still present
                newList.add(item);
            } else {
                // References a removed alias → prune
                changed = true;
            }
        }

        return newList;
    }

    /**
     * P0-3 #5: a "bare column reference" is a single-component {@link SqlIdentifier}
     * that is not a star — i.e. an unqualified column like {@code x} (as opposed to
     * a qualified {@code a.x} or a literal/function call). Ownership of such a
     * column by any particular table is not provable without a schema catalog.
     */
    private boolean isBareColumnReference(SqlNode item) {
        return item instanceof SqlIdentifier id && !id.isStar() && id.names.size() == 1;
    }

    // ── Identifier collection ──────────────────────────────────────────────────

    /**
     * Recursively collects table aliases referenced in an expression node.
     * An alias reference is a SqlIdentifier with more than 1 component (e.g., "e.first_name").
     * Simple identifiers (e.g., "e" in a FROM clause) are not counted as column refs.
     */
    private void collectAliasRefs(JoinCandidateSafety safety, SqlNode node, Set<String> result, Set<String> knownAliases) {
        result.addAll(safety.references(node,knownAliases));
    }

    // ── JOIN key column supplementation ─────────────────────────────────────────

    /**
     * Adds columns from ON conditions (that reference the removed table) to the SELECT list.
     * When a JOIN is split, the ON condition is discarded. This method finds columns
     * from remaining tables that appeared in those ON conditions and adds them to
     * the SELECT list, giving the user context about how the tables were connected.
     *
     * <p>Example: {@code ...ON D.DEPT_NO = DE.DEPT_NO} — when DE is removed,
     * adds {@code D.DEPT_NO} to the SELECT so the user can trace the relationship.
     */
    private void supplementJoinKeyColumns(JoinCandidateSafety safety, SqlNodeList selectList, SqlJoin join,
                                          String removedAlias, Set<String> remainingAliases) {
        // Collect existing select column names for dedup (e.g., "D.DEPT_NAME")
        Set<String> existingCols = new LinkedHashSet<>();
        for (SqlNode item : selectList) {
            if (item instanceof SqlIdentifier id && !id.isStar()) {
                existingCols.add(id.toString().toUpperCase());
            }
        }

        // Walk the join tree, find ON conditions referencing the removed alias
        List<SqlNode> onConditions = new ArrayList<>();
        collectOnConditionsForAlias(safety, join, removedAlias, onConditions);

        for (SqlNode onCond : onConditions) {
            List<SqlIdentifier> columns = extractRemainingColumnsFromOn(safety, onCond, remainingAliases);
            for (SqlIdentifier col : columns) {
                String colStr = col.toString().toUpperCase();
                if (!existingCols.contains(colStr)) {
                    selectList.add(col);
                    existingCols.add(colStr);
                }
            }
        }
    }

    /**
     * Recursively collects ON conditions from the join tree that reference the given alias.
     *
     * <p>P0-3 #4: only the join whose direct leaf child carries {@code alias} is
     * collected — that is the one join which collapses when the leaf is removed,
     * so its ON is truly discarded and its join keys deserve re-surfacing in the
     * SELECT list. Surviving joins keep their non-removed conjuncts in the rebuilt
     * ON, so harvesting keys from them would resurrect references the ON-cleaning
     * just removed (e.g. {@code C.B_ID} after removing {@code B}).
     */
    private void collectOnConditionsForAlias(JoinCandidateSafety safety, SqlNode node, String alias, List<SqlNode> result) {
        if (!(node instanceof SqlJoin join)) return;

        if (join.getConditionType() == JoinConditionType.ON && join.getCondition() != null) {
            Set<String> refs = new LinkedHashSet<>();
            collectAliasRefs(safety, join.getCondition(), refs, Set.of(alias));
            if (refs.contains(alias)
                && (isLeafWithAlias(safety, join.getLeft(), alias) || isLeafWithAlias(safety, join.getRight(), alias))) {
                result.add(join.getCondition());
            }
        }

        collectOnConditionsForAlias(safety, join.getLeft(), alias, result);
        collectOnConditionsForAlias(safety, join.getRight(), alias, result);
    }

    /** True if {@code side} is a leaf relation (not a nested join) aliased {@code alias}. */
    private boolean isLeafWithAlias(JoinCandidateSafety safety, SqlNode side, String alias) {
        if (side instanceof SqlJoin) return false;
        return extractAlias(safety, side).equals(alias);
    }

    /**
     * Extracts qualified column references (e.g., D.DEPT_NO) from an ON condition
     * that belong to remaining aliases.
     */
    private List<SqlIdentifier> extractRemainingColumnsFromOn(JoinCandidateSafety safety, SqlNode onCond, Set<String> remainingAliases) {
        List<SqlIdentifier> result = new ArrayList<>();
        extractColumnIdentifiers(safety, onCond, remainingAliases, result);
        return result;
    }

    private void extractColumnIdentifiers(JoinCandidateSafety safety, SqlNode node, Set<String> remainingAliases, List<SqlIdentifier> result) {
        if(node instanceof SqlSelect || node instanceof SqlWith || node instanceof SqlOrderBy)return;
        if (node instanceof SqlIdentifier id) {
            // Qualified column ref like D.DEPT_NO — first part is the alias
            if (!id.isStar() && id.names.size() >= 2) {
                String prefix = safety.key(id,0);
                if (remainingAliases.contains(prefix)) {
                    result.add(id);
                }
            }
        } else if (node instanceof SqlCall call) {
            for (SqlNode operand : call.getOperandList()) {
                if (operand != null) {
                    extractColumnIdentifiers(safety, operand, remainingAliases, result);
                }
            }
        } else if (node instanceof SqlNodeList list) {
            for (SqlNode item : list) {
                if (item != null) {
                    extractColumnIdentifiers(safety, item, remainingAliases, result);
                }
            }
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

    private JoinType joinTypeLabel(SqlJoin join) {
        try {
            return join.getJoinType();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot determine join type", e);
        }
    }

    private String relationName(SqlNode relation) {
        if (relation instanceof SqlIdentifier id) {
            return id.toString();
        }
        if (relation instanceof SqlSelect) {
            return "subquery";
        }
        if (relation instanceof SqlBasicCall call
            && call.getOperator() == SqlStdOperatorTable.AS
            && call.operandCount() >= 1) {
            String inner = relationName(call.operand(0));
            if (call.operandCount() >= 2 && call.operand(1) instanceof SqlIdentifier alias) {
                return inner + " AS " + alias.toString();
            }
            return inner;
        }
        return relation.getClass().getSimpleName().replace("Sql", "").toLowerCase();
    }

    private DialectProfile getProfile(String dialect) {
        return resolver.getProfile(dialect);
    }

    /**
     * Extracts the alias from a relation node (e.g., "EMPLOYEES AS E" → "E").
     * Returns the full name if no alias is present.
     */
    private String extractAlias(JoinCandidateSafety safety, SqlNode relation) {
        return safety.alias(relation);
    }

    // ── Internal types ──────────────────────────────────────────────────────────

    private record LeafRelation(SqlNode relation, JoinType joinType, String name, String alias) {}

    /** Mutable counter used during recursive AST traversal. */
    private static class LeafCounter {
        int current = 0;
    }
}
