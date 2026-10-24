package com.bitdata.sqlworkbench.diagnostics.trace;

/** A column-value pair identifying the row to trace. */
public record KeyValue(
    String column,
    Object value,
    Type type
) {
    public enum Type { TEXT, DECIMAL, BOOLEAN, NULL }
    public KeyValue(String column, Object value) { this(column, value, null); }
    public KeyValue {
        if (column == null || column.isBlank()) {
            throw new IllegalArgumentException("Column name must not be blank");
        }
        column = column.trim();
        TraceQueryBuilder.validateColumnName(column);
        if (type != null) {
            boolean valid = switch (type) {
                case TEXT -> value instanceof String;
                case DECIMAL -> value instanceof String s && s.length() <= 256 && s.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?");
                case BOOLEAN -> value instanceof Boolean;
                case NULL -> value == null;
            };
            if (!valid) throw new IllegalArgumentException("Value does not match key type " + type);
        } else if (value != null && !(value instanceof String || value instanceof Boolean || value instanceof Integer || value instanceof Long ||
                value instanceof Double d && Double.isFinite(d) || value instanceof java.math.BigDecimal)) {
            throw new IllegalArgumentException("Key value must be a finite scalar");
        }
    }
}
