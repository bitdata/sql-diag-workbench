package com.bitdata.sqlworkbench.sql;

import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.parser.SqlParseException;
import org.apache.calcite.sql.parser.SqlParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.regex.Pattern;

@Component
public class SqlParseEngine {

    private static final Logger log = LoggerFactory.getLogger(SqlParseEngine.class);

    private final SqlDialectResolver resolver;

    public SqlParseEngine(SqlDialectResolver resolver) {
        this.resolver = resolver;
    }

    // Calcite non-reserved keywords that the lexer won't treat as identifiers
    // after a dot. Pre-quoting them avoids "Encountered \". <keyword>\"" errors.
    private static final Set<String> QUOTED_KEYWORDS = Set.of(
        "sql", "date", "time", "timestamp", "user", "level", "type", "state",
        "value", "current", "session", "catalog", "schema", "language", "row",
        "size", "system", "table", "zone", "role", "module", "path",
        "year", "month", "day", "hour", "minute", "second", "position",
        "key", "order", "group", "name", "desc", "asc", "comment", "default",
        "offset", "limit", "fetch", "first", "last", "next", "prior",
        "read", "write", "call", "check", "constraint", "grant", "revoke",
        "trigger", "index", "view", "function", "procedure", "sequence"
    );

    // Matches .keyword where keyword is in the set, preceded by a word char
    // (so we don't match lone .keyword). Ensures we don't double-quote.
    private static final Pattern DOT_KEYWORD =
        Pattern.compile("(?<=\\.)(?i)(" + String.join("|", QUOTED_KEYWORDS) + ")(?=\\s|,|\\)|;|$|\\.|\\(|\\+|\\-|\\*|\\/|\\||!|=|<|>)");

    public SqlNode parse(String sql, String dialect) throws SqlParseException {
        log.info("Parsing SQL ({}): {}", dialect, sql.substring(0, Math.min(200, sql.length())));

        SqlParser.Config config = resolver.parserConfig(dialect);

        try {
            SqlParser parser = SqlParser.create(sql, config);
            return parser.parseQuery();
        } catch (SqlParseException e) {
            // Retry with dot-keywords quoted as identifiers
            String preprocessed = preProcessDotKeywords(sql, dialect);
            if (preprocessed.equals(sql)) {
                throw e;
            }
            log.debug("Retrying with dot-keywords quoted");
            SqlParser parser = SqlParser.create(preprocessed, config);
            return parser.parseQuery();
        }
    }

    public SqlNode parse(String sql) throws SqlParseException {
        return parse(sql, "postgresql");
    }

    /**
     * 对 SQL 中 {@code .keyword} 形式的非保留关键字加引号，跳过四种 quote 段：
     *
     * <ul>
     *   <li>字符串字面量 {@code '...'}（含 {@code ''} 转义）</li>
     *   <li>反引号标识符段 {@code `...`}（BACK_TICK — 闭引号前的 {@code ``} 表示字面反引号，
     *       与 {@code ""} 双引号转义同构；mysql 方言约定）</li>
     *   <li>双引号标识符段 {@code "..."}（DOUBLE_QUOTE — {@code ""} 转义，
     *       pg/oracle/SqlServer 方言约定）</li>
     *   <li>方括号标识符段 {@code [...]}（BRACKET — 无转义，sqlserver 方言约定）</li>
     * </ul>
     *
     * <p>引号字符来自该方言的 {@code DialectProfile.quoteChar()}（单一来源）；
     * 例如 postgresql 用双引号、mysql 用反引号、sqlserver 默认双引号（接受方括号）。
     * 此设计关闭 P2-12b 交互：P2-12a 曾硬编码双引号，在 P2-12b 把 mysql
     * 切到 BACK_TICK 后导致重试路径抛 {@code SqlParseException}。
     *
     * <p>M2 (2026-09-09)：扫描器改为识别全部四种 quote 段；此前仅跳过字符串
     * 字面量，致使 mysql {@code `a.table`}、pg {@code "a.table"}、sqlserver
     * {@code [a.table]} 中的 {@code .table} 被 DOT_KEYWORD 命中并二次包裹，
     * 产物破损。规则 #1 — 三个方言的 identifier-quote 段一并跳过。
     */
    String preProcessDotKeywords(String sql, String dialect) {
        char quoteChar = resolver.getProfile(dialect).quoteChar();
        String replacement = dotKeywordReplacement(quoteChar);
        StringBuilder out = new StringBuilder(sql.length() + 16);
        StringBuilder pending = new StringBuilder();
        int i = 0;
        int n = sql.length();
        while (i < n) {
            char c = sql.charAt(i);
            if (c == '\'') {
                // Flush literal-exterior accumulator
                out.append(DOT_KEYWORD.matcher(pending).replaceAll(replacement));
                pending.setLength(0);
                // Copy '...' verbatim with '' escape
                out.append(c);
                i++;
                while (i < n) {
                    char l = sql.charAt(i);
                    out.append(l);
                    i++;
                    if (l == '\'') {
                        if (i < n && sql.charAt(i) == '\'') {
                            out.append('\'');
                            i++;
                        } else {
                            break;
                        }
                    }
                }
            } else if (c == '`' || c == '"' || c == '[') {
                // Flush literal-exterior accumulator (segment content goes
                // straight to out, NOT through pending — otherwise DOT_KEYWORD
                // would see `.keyword` inside the segment on the next flush).
                out.append(DOT_KEYWORD.matcher(pending).replaceAll(replacement));
                pending.setLength(0);
                // Bracket-quoted identifiers use '[' opener + ']' closer;
                // back-tick and double-quote are symmetric (open == close).
                char closeChar = (c == '[') ? ']' : c;
                // Append opening quote to out before copying the segment,
                // so the inner loop only reads segment content.
                out.append(c);
                i++;
                while (i < n) {
                    char l = sql.charAt(i);
                    if (l == closeChar) {
                        // Pass i + 1 — the method's contract is the index AFTER
                        // the closing quote. Passing i made the DOUBLE_QUOTE
                        // check compare the quote against itself (always true),
                        // so no "..." segment ever terminated (final review I1).
                        boolean escaped = isClosingQuoteEscaped(sql, i + 1, c);
                        out.append(l);
                        i++;
                        if (!escaped) {
                            break;
                        }
                        // Doubled-quote escape (DOUBLE_QUOTE + BACK_TICK): consume the second
                        // quote. Backtick case is the MySQL identifier escape (`` `` `` = literal `` ` ``),
                        // wired in PR-1 (mysql-quoting-and-ledger-correction, 2026-09-17). The shape
                        // mirrors itself across both quote chars — same syntax, different meanings.
                        if ((c == '"' || c == '`') && i < n && sql.charAt(i) == c) {
                            out.append(c);
                            i++;
                        }
                        continue;
                    }
                    out.append(l);
                    i++;
                }
            } else {
                pending.append(c);
                i++;
            }
        }
        out.append(DOT_KEYWORD.matcher(pending).replaceAll(replacement));
        return out.toString();
    }

    /**
     * Returns true if the closing quote at position {@code afterCloseIdx - 1}
     * should NOT terminate the identifier-quote segment because it is escaped.
     *
     * <ul>
     *   <li>{@code `} (BACK_TICK): the next char is another {@code `} (MySQL's
     *       doubled-backtick identifier escape — ``a``b`` = literal `` ` `` inside the
     *       identifier). Wired in PR-1 (mysql-quoting-and-ledger-correction,
     *       2026-09-17); previously BACK_TICK_BACKSLASH used odd-backslash-run
     *       lookahead, which was wrong for MySQL's actual escape semantics.</li>
     *   <li>{@code "} (DOUBLE_QUOTE): next char is another {@code "} (SQL standard
     *       doubled-quote escape)</li>
     *   <li>{@code [} (BRACKET): never escaped — brackets have no internal escape</li>
     * </ul>
     */
    private static boolean isClosingQuoteEscaped(String sql, int afterCloseIdx, char quoteChar) {
        return switch (quoteChar) {
            case '`' -> afterCloseIdx < sql.length() && sql.charAt(afterCloseIdx) == '`';
            case '"' -> afterCloseIdx < sql.length() && sql.charAt(afterCloseIdx) == '"';
            case '[' -> false;
            default -> false;
        };
    }

    private static String dotKeywordReplacement(char quoteChar) {
        return switch (quoteChar) {
            case '`' -> "`$1`";
            case '[' -> "[$1]";
            default -> "\"$1\"";
        };
    }
}