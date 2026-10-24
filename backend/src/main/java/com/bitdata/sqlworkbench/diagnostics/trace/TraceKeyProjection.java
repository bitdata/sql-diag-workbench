package com.bitdata.sqlworkbench.diagnostics.trace;

import com.bitdata.sqlworkbench.sql.DialectProfile;
import java.sql.*;
import java.util.*;

/** Matches only projected output labels; it does not infer lineage or rename keys. */
public final class TraceKeyProjection {
    public record Match(List<String> labels, String reason, List<String> unavailableKeys) {}

    public static Match match(ResultSetMetaData meta, List<KeyValue> keys, DialectProfile profile) throws SQLException {
        List<String> labels = new ArrayList<>(), unavailable = new ArrayList<>();
        boolean ambiguous = false;
        for (KeyValue key : keys) {
            TraceQueryBuilder.validateColumnName(key.column());
            String bare = TraceQueryBuilder.bareColumnName(key.column());
            List<String> candidates = new ArrayList<>();
            for (int i = 1; i <= meta.getColumnCount(); i++) {
                String label = meta.getColumnLabel(i);
                if (profile.caseSensitive() ? bare.equals(label) : bare.equalsIgnoreCase(label)) candidates.add(label);
            }
            if (candidates.size() != 1) {
                unavailable.add(key.column());
                ambiguous |= candidates.size() > 1;
            } else labels.add(candidates.get(0));
        }
        return new Match(List.copyOf(labels), unavailable.isEmpty() ? null : ambiguous ? "AMBIGUOUS_KEY" : "KEY_NOT_PROJECTED", List.copyOf(unavailable));
    }
}
