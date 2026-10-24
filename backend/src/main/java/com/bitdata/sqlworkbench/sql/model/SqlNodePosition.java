package com.bitdata.sqlworkbench.sql.model;

/**
 * Represents a SQL AST node's source position and metadata.
 * Used for clickable SQL node selection in the editor.
 */
public record SqlNodePosition(
    String nodeId,
    String type,
    String label,
    SqlPosition start,
    SqlPosition end
) {

    public enum NodeType {
        TABLE_REF("Table Reference"),
        JOIN_CONDITION("JOIN Condition"),
        WHERE_CLAUSE("WHERE Clause"),
        COLUMN_REF("Column Reference"),
        SUBQUERY("Subquery"),
        CTE_DEFINITION("CTE Definition"),
        GROUP_BY("GROUP BY"),
        ORDER_BY("ORDER BY"),
        HAVING_CLAUSE("HAVING Clause"),
        SELECT_LIST("SELECT List");

        private final String displayName;

        NodeType(String displayName) {
            this.displayName = displayName;
        }

        public String displayName() {
            return displayName;
        }
    }
}