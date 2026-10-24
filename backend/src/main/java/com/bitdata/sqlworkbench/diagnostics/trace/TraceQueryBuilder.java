package com.bitdata.sqlworkbench.diagnostics.trace;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Generates parameterized EXISTS-wrapped SQL for trace queries
 * and binds key values to PreparedStatements.
 */
public class TraceQueryBuilder {

    private static final Pattern VALID_COLUMN = Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_.]*$");

    /**
     * Build a SELECT EXISTS(...) SQL wrapping the fragment SQL as a subquery.
     * Null values use IS NULL (no parameter placeholder).
     */
    public String buildExistsSql(String fragmentSql, List<KeyValue> keys) {
        return buildExistsSql(fragmentSql, keys, null, null);
    }

    public String buildExistsSql(String fragmentSql, List<KeyValue> keys, List<String> labels, Character quote) {
        return buildSql(fragmentSql, keys, labels, quote, false);
    }

    public String buildCountSql(String fragmentSql, List<KeyValue> keys, List<String> labels, Character quote) {
        return buildSql(fragmentSql, keys, labels, quote, true);
    }

    private String buildSql(String fragmentSql, List<KeyValue> keys, List<String> labels, Character quote, boolean count) {
        validateAllColumns(keys);

        StringBuilder where = new StringBuilder();
        boolean first = true;
        int columnIndex = 0;
        for (KeyValue kv : keys) {
            if (!first) where.append(" AND ");
            first = false;
            String col = labels == null ? bareColumnName(kv.column()) : labels.get(columnIndex);
            columnIndex++;
            if (quote != null) col = quote + col.replace(quote.toString(), quote.toString() + quote) + quote;
            if (kv.value() == null) {
                where.append(col).append(" IS NULL");
            } else {
                where.append(col).append(" = ?");
            }
        }

        return (count ? "SELECT COUNT(*) FROM (" : "SELECT EXISTS(SELECT 1 FROM (")
            + fragmentSql
            + ") AS __trace_sub WHERE "
            + where
            + (count ? "" : ") AS has_rows");
    }

    /**
     * WHERE against the derived table {@code __trace_sub} operates on bare
     * output column names, so an alias-qualified key column is reduced to its
     * last dot-segment. {@link #validateColumnName} still validates the full
     * input (alias qualification is legal); the bare suffix of a valid name is
     * itself valid.
     */
    static String bareColumnName(String column) {
        int dot = column.lastIndexOf('.');
        return dot >= 0 ? column.substring(dot + 1) : column;
    }

    /** Count non-null parameters (for PreparedStatement index tracking). */
    public int countParameters(List<KeyValue> keys) {
        return (int) keys.stream().filter(kv -> kv.value() != null).count();
    }

    /** Bind non-null key values to a PreparedStatement, starting at index 1. */
    public void bindParameters(PreparedStatement stmt, List<KeyValue> keys) throws SQLException {
        int idx = 1;
        for (KeyValue kv : keys) {
            if (kv.value() == null) continue;
            Object v = kv.value();
            if (kv.type() == KeyValue.Type.DECIMAL) {
                stmt.setBigDecimal(idx, new java.math.BigDecimal((String)v));
            } else if (v instanceof java.math.BigDecimal decimal) {
                stmt.setBigDecimal(idx, decimal);
            } else if (v instanceof Integer i) {
                stmt.setInt(idx, i);
            } else if (v instanceof Long l) {
                stmt.setLong(idx, l);
            } else if (v instanceof Double d) {
                stmt.setDouble(idx, d);
            } else if (v instanceof Boolean b) {
                stmt.setBoolean(idx, b);
            } else {
                stmt.setString(idx, v.toString());
            }
            idx++;
        }
    }

    /** Validate a single column name against SQL injection. */
    public static void validateColumnName(String column) {
        if (column == null || !VALID_COLUMN.matcher(column).matches()) {
            throw new IllegalArgumentException("Invalid column name: " + column);
        }
    }

    private void validateAllColumns(List<KeyValue> keys) {
        for (KeyValue kv : keys) {
            validateColumnName(kv.column());
        }
    }
}
