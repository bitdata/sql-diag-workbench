package com.bitdata.sqlworkbench.sql.decompose;

import com.bitdata.sqlworkbench.sql.DialectProfile;
import com.bitdata.sqlworkbench.sql.NodeToSql;

import org.apache.calcite.sql.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Analyzes CTE dependency relationships within a SqlWith AST.
 *
 * Walks each CTE definition's AST to find SqlIdentifier references in FROM/JOIN
 * contexts, building a directed graph of CTE→CTE dependencies. Supports computing
 * transitive closures and generating minimal WITH clauses that contain only
 * the required CTE definitions.
 */
@Component
public class CteDependencyGraph {

    private static final Logger log = LoggerFactory.getLogger(CteDependencyGraph.class);

    private final Map<String, CteInfo> cteByName;
    private final Map<String, Set<String>> directDeps;
    private final Set<String> selfReferences;

    public CteDependencyGraph() {
        this.cteByName = new LinkedHashMap<>();
        this.directDeps = new LinkedHashMap<>();
        this.selfReferences=new LinkedHashSet<>();
    }

    private CteDependencyGraph(Map<String, CteInfo> cteByName, Map<String, Set<String>> directDeps,Set<String> selfReferences) {
        this.cteByName = cteByName;
        this.directDeps = directDeps;
        this.selfReferences=selfReferences;
    }

    /** Build a CteDependencyGraph from a SqlWith node. */
    public static CteDependencyGraph build(SqlWith withNode, String dialect) {
        Map<String, CteInfo> cteByName = new LinkedHashMap<>();
        Map<String, Set<String>> directDeps = new LinkedHashMap<>();
        Set<String> selfReferences=new LinkedHashSet<>();

        if (withNode.withList != null) {
            for (SqlNode item : withNode.withList) {
                if (item instanceof SqlWithItem withItem) {
                    String name = withItem.name.toString().toUpperCase();
                    CteInfo info = new CteInfo(name, withItem.query, withItem);
                    cteByName.put(name, info);
                    directDeps.put(name, new LinkedHashSet<>());
                }
            }
        }

        // Walk each CTE to find references to other CTEs
        for (Map.Entry<String, CteInfo> entry : cteByName.entrySet()) {
            String cteName = entry.getKey();
            SqlNode query = entry.getValue().definition();
            Set<String> refs = collectCteRefs(query, cteByName.keySet());
            // Keep recursive facts separately: self is not "referenced by others",
            // but extracting this body without its defining WITH would be unsafe.
            if(refs.contains(cteName))selfReferences.add(cteName);
            refs.remove(cteName);
            directDeps.get(cteName).addAll(refs);
        }

        return new CteDependencyGraph(cteByName, directDeps,selfReferences);
    }

    /** True for self-recursion or a transitive dependency on any recursive group. */
    public boolean requiresRecursiveClosure(String name) {
        String key=name.toUpperCase(Locale.ROOT);
        Set<String> closure=new LinkedHashSet<>(transitiveDependencies(key));closure.add(key);
        for(String dep:closure)if(selfReferences.contains(dep)||transitiveDependencies(dep).contains(dep))return true;
        return false;
    }

    /** All CTE names in the graph. */
    public Set<String> allCteNames() {
        return cteByName.keySet();
    }

    /**
     * Compute transitive CTE dependencies using BFS.
     * Self-referencing CTEs are handled via visited set.
     */
    public Set<String> transitiveDependencies(String cteName) {
        Set<String> result = new LinkedHashSet<>();
        Set<String> visited = new HashSet<>();
        Queue<String> queue = new LinkedList<>();

        String upper = cteName.toUpperCase();
        Set<String> direct = directDeps.getOrDefault(upper, Set.of());
        queue.addAll(direct);

        while (!queue.isEmpty()) {
            String dep = queue.poll();
            if (visited.add(dep)) {
                result.add(dep);
                Set<String> transitive = directDeps.getOrDefault(dep, Set.of());
                for (String t : transitive) {
                    if (!visited.contains(t)) {
                        queue.add(t);
                    }
                }
            }
        }
        return result;
    }

    /**
     * Build a minimal WITH clause containing only the required CTE definitions,
     * in dependency order. Unknown CTE names are silently ignored.
     */
    public String buildMinimalWithClause(Set<String> requiredCteNames, DialectProfile profile) {
        if (requiredCteNames.isEmpty()) return "";

        // Collect all needed CTEs (required + their transitive deps)
        Set<String> needed = new LinkedHashSet<>();
        for (String name : requiredCteNames) {
            String upper = name.toUpperCase();
            needed.add(upper);
            needed.addAll(transitiveDependencies(upper));
        }

        // Topological sort: CTEs whose deps are satisfied come first
        List<String> sorted = new ArrayList<>();
        Set<String> remaining = new LinkedHashSet<>(needed);
        Set<String> placed = new HashSet<>();

        while (!remaining.isEmpty()) {
            boolean progress = false;
            for (String cteName : new ArrayList<>(remaining)) {
                Set<String> deps = directDeps.getOrDefault(cteName, Set.of());
                if (placed.containsAll(deps)) {
                    sorted.add(cteName);
                    placed.add(cteName);
                    remaining.remove(cteName);
                    progress = true;
                }
            }
            if (!progress) {
                log.warn("Cycle or missing deps detected, adding remaining: {}", remaining);
                sorted.addAll(remaining);
                break;
            }
        }

        // Build WITH clause
        StringBuilder sb = new StringBuilder("WITH ");
        boolean first = true;
        for (String name : sorted) {
            CteInfo info = cteByName.get(name);
            if (info == null) continue;
            if (!first) sb.append(", ");
            SqlWithItem item=info.reference();
            sb.append(NodeToSql.toSql(item.name,profile));
            if(item.columnList!=null){
                StringJoiner columns=new StringJoiner(", "," (",")");
                for(SqlNode column:item.columnList)columns.add(NodeToSql.toSql(column,profile));
                sb.append(columns);
            }
            sb.append(" AS (").append(NodeToSql.toSql(item.query,profile)).append(")");
            first = false;
        }
        return sb.toString();
    }

    /**
     * Compute strongly-connected components (SCCs) of the CTE dependency
     * graph using Tarjan's iterative algorithm.
     *
     * <p>Returns ONLY the SCCs that require special handling by callers —
     * size ≥ 2 (mutual-recursive groups). Singletons without a self-loop
     * are filtered because {@code build()} already removes self-loops via
     * {@code refs.remove(cteName)} (P1-9b fix), so every size-1 SCC from
     * Tarjan is structurally non-recursive and should be handled by the
     * normal chain/standalone emission path.
     *
     * <p>Order: SCCs are returned in reverse topological order of the
     * SCC condensation (Tarjan's natural output) — i.e. an SCC that
     * depends on another SCC appears first in the returned list. Callers
     * that want a forward topological order can reverse the result.
     *
     * <p>P1-9b (extension): mutual-recursive CTEs ({@code v1→v2, v2→v1})
     * previously made {@link #transitiveDependencies} walk back via the
     * back-edge and add the calling CTE to its own closure, which fed
     * back into {@code referencedByOthers} and caused {@code CteExpansionRule}
     * to silently skip the whole SCC. Co-emission (one fragment per SCC)
     * is the correct response — SQL standard forbids splitting mutual-
     * recursive CTEs across separate {@code WITH} blocks.
     */
    public List<Set<String>> stronglyConnectedComponents() {
        List<Set<String>> result = new ArrayList<>();

        // Tarjan state
        Map<String, Integer> indices = new LinkedHashMap<>();
        Map<String, Integer> lowlinks = new LinkedHashMap<>();
        Deque<String> stack = new ArrayDeque<>();
        Set<String> onStack = new HashSet<>();
        int[] counter = {0};

        // Iterative worklist — explicit stack avoids JVM stack overflow on
        // deep dependency chains (defensive; CTE graphs are typically tiny).
        Deque<Frame> worklist = new ArrayDeque<>();

        for (String start : cteByName.keySet()) {
            if (indices.containsKey(start)) continue;
            worklist.push(new Frame(start));
            indices.put(start, counter[0]);
            lowlinks.put(start, counter[0]);
            counter[0]++;
            stack.push(start);
            onStack.add(start);

            while (!worklist.isEmpty()) {
                Frame frame = worklist.peek();
                String v = frame.node;
                List<String> deps = frame.deps;

                // Try to advance into the next unvisited child.
                // IMPORTANT: read & update frame.iter directly (not via a
                // local snapshot). A snapshot becomes stale once we descend
                // into a child and return — the local `iter` was always 0,
                // so `while (iter < deps.size())` would loop forever on the
                // first dep. Verified via jstack of a hung test run.
                boolean descended = false;
                while (frame.iter < deps.size()) {
                    String w = deps.get(frame.iter);
                    frame.iter++;
                    if (!indices.containsKey(w)) {
                        // Tree edge — descend
                        indices.put(w, counter[0]);
                        lowlinks.put(w, counter[0]);
                        counter[0]++;
                        stack.push(w);
                        onStack.add(w);
                        worklist.push(new Frame(w));
                        descended = true;
                        break;
                    }
                    if (onStack.contains(w)) {
                        // Back edge — update lowlink from child's index
                        lowlinks.put(v, Math.min(lowlinks.get(v), indices.get(w)));
                    }
                }
                if (descended) {
                    continue;
                }

                // All descendants processed — propagate lowlink up
                // (will be picked up by parent's lowlink update after pop below).

                // Root of an SCC?
                if (lowlinks.get(v).equals(indices.get(v))) {
                    Set<String> scc = new LinkedHashSet<>();
                    while (true) {
                        String w = stack.pop();
                        onStack.remove(w);
                        scc.add(w);
                        if (w.equals(v)) break;
                    }
                    // Only emit SCCs that require co-emission handling.
                    // Singletons without self-loop fall through to the
                    // standard chain/standalone path in CteExpansionRule.
                    if (scc.size() > 1) {
                        result.add(scc);
                    }
                }

                worklist.pop();

                // After v completes, propagate v's lowlink up to the parent
                // (if any). This is how tree-edge lowlinks converge on the
                // actual SCC root.
                if (!worklist.isEmpty()) {
                    Frame parent = worklist.peek();
                    lowlinks.put(parent.node, Math.min(lowlinks.get(parent.node), lowlinks.get(v)));
                }
            }
        }

        return result;
    }

    /** Iterative-Tarjan stack frame — carries pre-snapshotted child list and the iterator cursor. */
    private final class Frame {
        final String node;
        final List<String> deps;
        int iter;

        Frame(String node) {
            this.node = node;
            this.deps = new ArrayList<>(directDeps.getOrDefault(node, Set.of()));
            this.iter = 0;
        }
    }

    /**
     * Collect CTE name references from a SqlNode by walking its AST
     * and finding SqlIdentifier nodes used as table references in FROM/JOIN.
     */
    public static Set<String> collectCteRefs(SqlNode node, Set<String> cteNames) {
        Set<String> refs = new LinkedHashSet<>();
        if (cteNames.isEmpty() || node == null) return refs;
        collectCteRefsImpl(node, cteNames, refs, false);
        return refs;
    }

    /**
     * Walk the AST recording CTE names that appear as table references.
     *
     * <p>The {@code inTableContext} flag is true only when {@code node} is a
     * direct FROM/JOIN operand — i.e. a position where a bare identifier
     * genuinely names a table/CTE. It is threaded down rather than inferred
     * from the parent node because the {@code AS} operator is ambiguous:
     * {@code FROM t AS a} (table + alias) and {@code SELECT c AS a}
     * (column + alias) share it. Threading the position lets us descend into
     * select-list / WHERE / HAVING (so CTE refs nested inside their
     * subqueries are found) while still filtering out bare column identifiers
     * whose name collides with a CTE — the misclassification the original
     * P1-9a fix avoided only by not descending at all (which dropped genuine
     * nested refs → invalid standalone fragments, the P1-9a regression).
     */
    private static void collectCteRefsImpl(SqlNode node, Set<String> cteNames,
                                           Set<String> refs, boolean inTableContext) {
        if (node == null) return;

        if (node instanceof SqlSelect select) {
            // A query's own FROM is a table context regardless of where the
            // query itself sits — a subquery in a column position still has
            // real tables in its FROM. The surrounding flag does not apply.
            collectCteRefsImpl(select.getFrom(), cteNames, refs, true);
            // WHERE / HAVING / select-list are column-expression contexts:
            // descend (subqueries inside carry their own FROM context) but
            // bare identifiers there are not table references.
            collectCteRefsImpl(select.getWhere(), cteNames, refs, false);
            collectCteRefsImpl(select.getHaving(), cteNames, refs, false);
            collectCteRefsImpl(select.getSelectList(), cteNames, refs, false);
            return;
        }

        if (node instanceof SqlJoin join) {
            collectCteRefsImpl(join.getLeft(), cteNames, refs, true);
            collectCteRefsImpl(join.getRight(), cteNames, refs, true);
            // join condition is a boolean expression, not a table context;
            // subqueries inside it reset their own FROM when reached.
            collectCteRefsImpl(join.getCondition(), cteNames, refs, false);
            return;
        }

        if (node instanceof SqlIdentifier id) {
            if (inTableContext) {
                String name = id.toString().toUpperCase();
                if (cteNames.contains(name)) {
                    refs.add(name);
                }
            }
            return;
        }

        if (node instanceof SqlNodeList list) {
            for (SqlNode item : list) {
                collectCteRefsImpl(item, cteNames, refs, inTableContext);
            }
            return;
        }

        if (node instanceof SqlBasicCall call) {
            // AS distinguishes by position: only operand 0 (the aliased
            // entity) can be a table reference, and only when the AS call
            // itself is in a table context; the alias name (operand 1) never
            // is. This separates `FROM t AS a` (operand 0 = table) from
            // `SELECT c AS a` (operand 0 = column) without parent sniffing.
            boolean isAs = call.getOperator().getName().equals("AS");
            List<SqlNode> operands = call.getOperandList();
            for (int i = 0; i < operands.size(); i++) {
                boolean childCtx = isAs ? (i == 0 && inTableContext) : false;
                collectCteRefsImpl(operands.get(i), cteNames, refs, childCtx);
            }
            return;
        }

        // Other container calls (SqlOrderBy, set operators, ...) — descend
        // with a non-table context so nested subqueries are still walked.
        if (node instanceof SqlCall call) {
            for (SqlNode operand : call.getOperandList()) {
                collectCteRefsImpl(operand, cteNames, refs, false);
            }
        }
    }

    /** Info about a single CTE definition. */
    public record CteInfo(
        String name,
        SqlNode definition,
        SqlWithItem reference
    ) {}
}
