package com.bitdata.sqlworkbench.sql;

import org.apache.calcite.sql.SqlNode;

import java.util.Set;

/**
 * Converts a Calcite SqlNode back to SQL text for a target dialect, then
 * removes identifier quotes where safe.
 *
 * <p>P0-1: the scanner is literal-aware. Single-quoted string literals
 * (including '' escapes) are copied verbatim, so the identifier-unquote pass
 * can never rewrite literal content.
 *
 * <p>Unquote rule: an identifier loses its quotes only if its content is
 * single-case (all lowercase or all uppercase), matches the plain-identifier
 * pattern, and is not reserved by the dialect. Mixed-case content keeps its
 * quotes — dropping them would case-fold the name to a different column.
 */
public class NodeToSql {

    public static String toSql(SqlNode node, DialectProfile profile) {
        String sql = node.toSqlString(profile.calciteDialect()).getSql();
        return unquoteIfSafe(sql, profile.quoteChar(), profile.reservedKeywords());
    }

    private static String unquoteIfSafe(String sql, char quoteChar, Set<String> reserved) {
        StringBuilder result = new StringBuilder();
        int i = 0;
        boolean inLiteral = false;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (inLiteral) {
                result.append(c);
                if (c == '\'') {
                    if (i + 1 < sql.length() && sql.charAt(i + 1) == '\'') {
                        result.append('\'');
                        i += 2;
                        continue;
                    }
                    inLiteral = false;
                }
                i++;
                continue;
            }
            if (c == '\'') {
                inLiteral = true;
                result.append(c);
                i++;
                continue;
            }
            if (c == quoteChar) {
                int end = findClosingQuote(sql, quoteChar, i + 1);
                if (end < 0) {
                    result.append(sql, i, sql.length());
                    break;
                }
                String identifier = sql.substring(i + 1, end)
                    .replace("" + quoteChar + quoteChar, "" + quoteChar);
                if (isUnquotable(identifier, reserved)) {
                    result.append(identifier);
                } else {
                    result.append(quoteChar)
                        .append(identifier.replace("" + quoteChar, "" + quoteChar + quoteChar))
                        .append(quoteChar);
                }
                i = end + 1;
                continue;
            }
            result.append(c);
            i++;
        }
        return result.toString();
    }

    /** Finds the closing quote, treating a doubled quote char as an escape. */
    private static int findClosingQuote(String sql, char quoteChar, int from) {
        int i = from;
        while (i < sql.length()) {
            if (sql.charAt(i) == quoteChar) {
                if (i + 1 < sql.length() && sql.charAt(i + 1) == quoteChar) {
                    i += 2;
                    continue;
                }
                return i;
            }
            i++;
        }
        return -1;
    }

    private static boolean isUnquotable(String identifier, Set<String> reserved) {
        if (identifier.isEmpty()) {
            return false;
        }
        if (reserved.contains(identifier.toLowerCase())) {
            return false;
        }
        if (!identifier.matches("[a-zA-Z_][a-zA-Z0-9_]*")) {
            return false;
        }
        boolean allLower = identifier.equals(identifier.toLowerCase());
        boolean allUpper = identifier.equals(identifier.toUpperCase());
        return allLower || allUpper;
    }
}
