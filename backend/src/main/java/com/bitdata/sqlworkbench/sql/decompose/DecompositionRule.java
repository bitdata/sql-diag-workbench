package com.bitdata.sqlworkbench.sql.decompose;

import com.bitdata.sqlworkbench.sql.model.DecomposeOption;

import org.apache.calcite.sql.SqlNode;

import java.util.List;

public interface DecompositionRule {
    /** Internal rule identifier, used as FragmentNode.ruleName */
    String name();

    /** Human-readable short name for UI buttons (e.g., "Strip Clause") */
    String displayName();

    /** One-line explanation of what this rule does */
    String description();

    /**
     * The semantic level at which this rule operates.
     * Used to group rules by depth in the UI.
     */
    default DecompositionLevel level() {
        return DecompositionLevel.STRUCTURE;
    }


    /** Apply this rule to an AST node, returning simplified/decomposed SQL fragments. */
    List<DecomposedFragment> apply(SqlNode root, String dialect);

    default RuleEvaluation evaluate(SqlNode root, String dialect) {
        return new RuleEvaluation(apply(root, dialect), List.of());
    }

    /**
     * Returns the decomposition options this rule offers for the given node.
     * Default implementation tries applying the rule and returns an option if
     * it produces fragments.
     */
    default List<DecomposeOption> getAvailableOptions(SqlNode root, String dialect) {
            RuleEvaluation result = evaluate(root, dialect);
            if (!result.fragments().isEmpty() || !result.issues().isEmpty()) {
                return List.of(new DecomposeOption(name(), displayName(), description(), level()));
            }
        return List.of();
    }
}
