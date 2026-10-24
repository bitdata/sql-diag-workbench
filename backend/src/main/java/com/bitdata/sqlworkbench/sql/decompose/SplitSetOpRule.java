package com.bitdata.sqlworkbench.sql.decompose;

import com.bitdata.sqlworkbench.sql.DialectProfile;
import com.bitdata.sqlworkbench.sql.NodeToSql;
import com.bitdata.sqlworkbench.sql.SqlDialectResolver;
import com.bitdata.sqlworkbench.sql.model.FragmentType;

import org.apache.calcite.sql.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * P0 rule: splits UNION / UNION ALL / INTERSECT / EXCEPT into individual branches.
 *
 * Each branch is a standalone, executable SELECT statement.
 * Chained set ops of the same type are flattened (e.g., A UNION ALL B UNION ALL C → 3 branches).
 */
@Component
public class SplitSetOpRule implements DecompositionRule {

    private static final Logger log = LoggerFactory.getLogger(SplitSetOpRule.class);
    private static final Set<String> SET_OPS = Set.of("UNION", "UNION ALL", "INTERSECT", "EXCEPT");

    private final SqlDialectResolver resolver;

    public SplitSetOpRule(SqlDialectResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    public String name() {
        return "set-op-split";
    }

    @Override
    public String displayName() {
        return "Split Set Operation";
    }

    @Override
    public DecompositionLevel level() {
        return DecompositionLevel.STRUCTURE;
    }

    @Override
    public String description() {
        return "Break UNION/INTERSECT/EXCEPT into individual branches to find where data changes";
    }

    @Override
    public List<DecomposedFragment> apply(SqlNode root, String dialect) {
        List<DecomposedFragment> fragments = new ArrayList<>();

        // Unwrap SqlOrderBy to get the inner query
        SqlNode inner = root;
        SqlOrderBy orderBy = null;
        if (root instanceof SqlOrderBy ob) {
            orderBy = ob;
            inner = ob.query;
        }

        if (!(inner instanceof SqlBasicCall call)) {
            return fragments;
        }

        String opName = call.getOperator().getName().toUpperCase();
        if (!SET_OPS.contains(opName)) {
            return fragments;
        }

        DialectProfile profile = getProfile(dialect);

        // Flatten chained set ops of the same type
        List<SqlNode> branches = new ArrayList<>();
        flattenBranches(call, opName, branches);

        String kind = opName;
        for (int i = 0; i < branches.size(); i++) {
            SqlNode operand = branches.get(i);
            if (operand == null) continue;

            // P1-8: set-op-level ORDER BY / FETCH / OFFSET belong to the set
            // result, not to individual branches. Carrying them into each
            // branch produces invalid SQL when the order key references a
            // column or alias only present in another branch (and discards
            // FETCH into the bargain). Branch fragments are standalone
            // branch queries.
            String sql = NodeToSql.toSql(operand, profile);

            fragments.add(new DecomposedFragment(
                FragmentType.SET_SPLIT,
                kind + " — branch " + (i + 1),
                sql,
                name(),
                true,
                "Standalone branch from " + kind,
                List.of()
            ));
        }

        return fragments;
    }

    /**
     * Recursively flatten chained set ops of the same type into a flat list of branches.
     * A UNION ALL B UNION ALL C → [A, B, C]
     * For different set op types, does NOT flatten across type boundaries.
     */
    private void flattenBranches(SqlBasicCall call, String opName, List<SqlNode> result) {
        for (SqlNode operand : call.getOperandList()) {
            if (operand instanceof SqlBasicCall subCall
                && subCall.getOperator().getName().toUpperCase().equals(opName)) {
                flattenBranches(subCall, opName, result);
            } else {
                result.add(operand);
            }
        }
    }

    private DialectProfile getProfile(String dialect) {
        return resolver.getProfile(dialect);
    }
}
