package com.bitdata.sqlworkbench.sql.decompose;

import com.bitdata.sqlworkbench.sql.DialectProfile;
import com.bitdata.sqlworkbench.sql.NodeToSql;
import com.bitdata.sqlworkbench.sql.SqlDialectResolver;
import com.bitdata.sqlworkbench.sql.model.FragmentType;
import com.bitdata.sqlworkbench.sql.model.DecompositionResult.Issue;

import org.apache.calcite.sql.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Extracts CTE definitions as standalone executable queries.
 *
 * Semantics — "decomposition = remove the non-CTE part" (docs/decomposition-principles.md):
 * <ul>
 *   <li>The body of the {@code WITH ... SELECT} query is dropped — CTE expansion only
 *       surfaces the CTE definitions themselves.</li>
 *   <li>Minimum-split (same-method): a CTE that is transitively depended-on by another
 *       sibling CTE is NOT emitted as a direct sibling — the user reaches it by
 *       re-applying Expand CTE on the dependent sibling.</li>
 *   <li>A CTE with transitive deps is emitted as {@code WITH <deps...> <its body>}
 *       (inline form). A standalone CTE is emitted as just its body SELECT.</li>
 *   <li>Orphan CTEs (defined but unused) are still emitted — faithful to the original SQL.</li>
 * </ul>
 */
@Component
public class CteExpansionRule implements DecompositionRule {

    private static final Logger log = LoggerFactory.getLogger(CteExpansionRule.class);

    private final SqlDialectResolver resolver;

    public CteExpansionRule(SqlDialectResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    public String name() {
        return "cte-expansion";
    }

    @Override
    public String displayName() {
        return "Expand CTE";
    }

    @Override
    public DecompositionLevel level() {
        return DecompositionLevel.STRUCTURE;
    }

    @Override
    public String description() {
        return "Extract each CTE definition as a standalone executable query (drops the body)";
    }

    @Override
    public List<DecomposedFragment> apply(SqlNode root, String dialect) {
        RuleEvaluation result=evaluate(root,dialect);
        if(!result.issues().isEmpty())throw new IllegalStateException(result.issues().toString());
        return result.fragments();
    }

    @Override
    public RuleEvaluation evaluate(SqlNode root,String dialect) {
        List<DecomposedFragment> fragments = new ArrayList<>();
        List<Issue> issues=new ArrayList<>();

        // Unwrap optional ORDER BY wrapper — CTE decomposition discards body
        // ordering anyway (the body is dropped), so we only need to reach SqlWith.
        SqlNode inner = (root instanceof SqlOrderBy ob) ? ob.query : root;

        if (!(inner instanceof SqlWith with) || with.withList == null) {
            return new RuleEvaluation(fragments,issues);
        }

        DialectProfile profile = getProfile(dialect);
        CteDependencyGraph graph = CteDependencyGraph.build(with, dialect);

        // P1-9b (extension): pre-compute SCCs for mutual-recursive CTE groups.
        // Each SCC of size > 1 is co-emitted as one fragment (mutual-recursive
        // CTEs cannot be split across separate WITH blocks — the SQL standard
        // requires all members of the cycle to be defined together for the
        // recursive materialization to resolve).
        List<Set<String>> sccs = graph.stronglyConnectedComponents();
        Map<String, Set<String>> sccOf = new HashMap<>();
        for (Set<String> scc : sccs) {
            for (String member : scc) {
                sccOf.put(member, scc);
            }
        }

        // Minimum-split: CTE names transitively depended-on by any other CTE.
        // Such CTEs are reachable via a sibling's recursive Expand CTE, so we
        // do not emit them directly here.
        //
        // EXCEPTION (P1-9b extension): for CTE names that belong to an SCC of
        // size ≥ 2, BFS would walk back via the cycle and include the name in
        // its own closure — `referencedByOthers` is therefore unreliable for
        // SCC members. They are co-emitted below regardless.
        Set<String> referencedByOthers = new LinkedHashSet<>();
        Set<String> sccMembers = new HashSet<>();
        for (Set<String> scc : sccs) {
            sccMembers.addAll(scc);
        }
        for (String name : graph.allCteNames()) {
            if (sccMembers.contains(name)) {
                // SCC members are handled by SCC co-emission below — skip
                // their transitive-closure computation here.
                continue;
            }
            referencedByOthers.addAll(graph.transitiveDependencies(name));
        }

        Set<String> emitted = new HashSet<>();
        for (int itemIndex=0;itemIndex<with.withList.size();itemIndex++) {
            SqlNode itemNode=with.withList.get(itemIndex);
            if (!(itemNode instanceof SqlWithItem item)) continue;

            String alias = item.name.toString();
            String aliasKey = alias.toUpperCase();
            if (emitted.contains(aliasKey)) {
                continue;
            }

            // SCC co-emission (P1-9b extension): mutual-recursive CTEs form an
            // indivisible expansion unit — one fragment per SCC.
            Set<String> scc = sccOf.get(aliasKey);
            if (scc != null && scc.size() > 1) {
                fragments.add(buildSccFragment(scc, with, profile));
                emitted.addAll(scc);
                continue;
            }

            if (referencedByOthers.contains(aliasKey)) {
                log.debug("Skipping CTE '{}' as direct sibling — surfaces via another sibling's Expand CTE", alias);
                continue;
            }

            if(graph.requiresRecursiveClosure(aliasKey)) {
                issues.add(new Issue("UNRESOLVED_DEPENDENCY","CTE extraction requires a recursive dependency closure; the original query remains independently executable",name(),"query/cte["+itemIndex+"]",null,NodeToSql.toSql(item.query,profile)));
                emitted.add(aliasKey);
                continue;
            }

            Set<String> deps = graph.transitiveDependencies(aliasKey);
            String bodySql = NodeToSql.toSql(item.query, profile);

            String label;
            String sql;
            String hint;
            if (deps.isEmpty()) {
                label = "CTE: " + alias;
                sql = bodySql;
                hint = "Standalone CTE definition — run directly on the database";
            } else {
                label = "CTE: " + alias + " (with deps)";
                sql = graph.buildMinimalWithClause(deps, profile) + " " + bodySql;
                hint = "CTE with " + deps.size() + " transitive dependency(ies) included";
            }

            fragments.add(new DecomposedFragment(
                FragmentType.CTE_EXPANDED,
                label,
                sql,
                name(),
                true,
                hint,
                List.of()
            ));
            emitted.add(aliasKey);
        }

        return new RuleEvaluation(fragments,issues);
    }

    /**
     * Build a single fragment containing the WITH clause definitions of all
     * members of an SCC (mutual-recursive CTE group). The fragment is emitted
     * with NO body SELECT — mutual-recursive CTE bodies reference one another,
     * so no single body can stand alone. The user inspects individual CTE
     * definitions by re-applying Expand CTE on the resulting fragment.
     *
     * <p>Members appear in the order they were declared in {@code with.withList}
     * (preserving the user's SQL structure).
     */
    private DecomposedFragment buildSccFragment(Set<String> scc, SqlWith with, DialectProfile profile) {
        StringBuilder sb = new StringBuilder("WITH ");
        boolean first = true;
        int memberCount = 0;
        for (SqlNode itemNode : with.withList) {
            if (!(itemNode instanceof SqlWithItem item)) continue;
            String aliasKey = item.name.toString().toUpperCase();
            if (!scc.contains(aliasKey)) continue;
            String alias = item.name.toString();
            if (!first) sb.append(", ");
            sb.append(alias).append(" AS (").append(NodeToSql.toSql(item.query, profile)).append(")");
            first = false;
            memberCount++;
        }

        // Deterministic, SCC-friendly label (members sorted alphabetically)
        List<String> sortedMembers = new ArrayList<>(scc);
        java.util.Collections.sort(sortedMembers);
        String label = "CTE SCC: " + String.join("+", sortedMembers) + " (mutually recursive)";
        String hint = "Mutually-recursive CTE group (" + memberCount + " members) — co-emitted per WITH block; " +
            "SQL standard requires all members of a recursive cycle to be defined together";

        return new DecomposedFragment(
            FragmentType.CTE_EXPANDED,
            label,
            sb.toString(),
            name(),
            // Not executable: a bodyless `WITH` cannot be parsed/run standalone
            // (see the javadoc above). Marking it executable fed a run affordance
            // (frontend Run/Analyze gates) that could only fail, and made the
            // row-trace S5 single-SELECT validation reject the whole trace.
            false,
            hint,
            List.of()
        );
    }

    private DialectProfile getProfile(String dialect) {
        return resolver.getProfile(dialect);
    }
}
