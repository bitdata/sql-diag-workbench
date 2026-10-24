package com.bitdata.sqlworkbench.diagnostics;

/**
 * Lifecycle status of a diagnostic finding.
 *
 * <ul>
 *   <li>{@code SUSPECTED} — initial state when a rule produces the finding</li>
 *   <li>{@code CONFIRMED} — verification SQL execution confirmed the issue (rowCount > 0)</li>
 *   <li>{@code REFUTED} — verification SQL execution showed the issue is not present (rowCount == 0)</li>
 * </ul>
 */
public enum FindingStatus {
    SUSPECTED,
    CONFIRMED,
    REFUTED
}
