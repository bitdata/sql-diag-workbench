package com.bitdata.sqlworkbench.sql.model;

/**
 * Represents a position in the original SQL source text.
 * Line and column are 1-based (matching Calcite SqlParserPos).
 */
public record SqlPosition(int line, int column, int offset) {

    public static SqlPosition fromLineColumn(int line, int column, String sql) {
        int offset = computeOffset(sql, line, column);
        return new SqlPosition(line, column, offset);
    }

    private static int computeOffset(String sql, int targetLine, int targetColumn) {
        int currentLine = 1;
        int currentCol = 1;
        for (int i = 0; i < sql.length(); i++) {
            if (currentLine == targetLine && currentCol == targetColumn) {
                return i;
            }
            char c = sql.charAt(i);
            if (c == '\n') {
                currentLine++;
                currentCol = 1;
            } else {
                currentCol++;
            }
        }
        return sql.length();
    }
}