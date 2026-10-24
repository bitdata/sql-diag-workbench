package com.bitdata.sqlworkbench.sql.decompose;

import com.bitdata.sqlworkbench.sql.model.FragmentType;

import java.util.List;

/**
 * Output of a DecompositionRule — a simpler, executable SQL derived from a parent.
 * Children are empty when first returned; the engine populates them by re-parsing
 * and applying rules recursively.
 */
public record DecomposedFragment(
    FragmentType type,
    String label,
    String sqlFragment,
    String ruleName,
    boolean executable,
    String executionHint,
    List<DecomposedFragment> children,
    com.bitdata.sqlworkbench.sql.model.DecompositionResult.Effect effect
) {
    public DecomposedFragment(FragmentType type, String label, String sqlFragment, String ruleName,
                              boolean executable, String executionHint, List<DecomposedFragment> children) {
        this(type, label, sqlFragment, ruleName, executable, executionHint, children, null);
    }
}
