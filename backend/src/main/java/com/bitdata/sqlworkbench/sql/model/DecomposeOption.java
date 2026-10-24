package com.bitdata.sqlworkbench.sql.model;

import com.bitdata.sqlworkbench.sql.decompose.DecompositionLevel;

/**
 * A decomposition strategy available for a given SQL node.
 * Presented to the user as a button/link in the FragmentTree UI.
 */
public record DecomposeOption(
    String ruleName,
    String displayName,
    String description,
    DecompositionLevel level
) {
    public DecomposeOption(String ruleName, String displayName, String description) {
        this(ruleName, displayName, description, DecompositionLevel.STRUCTURE);
    }
}
