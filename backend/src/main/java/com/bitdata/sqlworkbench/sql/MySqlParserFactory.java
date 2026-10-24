package com.bitdata.sqlworkbench.sql;

import org.apache.calcite.sql.parser.SqlParserImplFactory;
import org.apache.calcite.sql.parser.SqlAbstractParserImpl.LexicalState;
import org.apache.calcite.sql.parser.impl.SimpleCharStream;
import org.apache.calcite.sql.parser.impl.SqlParserImpl;
import org.apache.calcite.sql.parser.impl.SqlParserImplConstants;
import org.apache.calcite.sql.parser.impl.SqlParserImplTokenManager;
import org.apache.calcite.sql.parser.impl.Token;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Calcite 1.37's BTID lexer omits MySQL's double-quoted strings (including
 * SELECT aliases). Feed it same-length string placeholders, restoring each
 * token's decoded value before grammar consumption. Original token/AST/error
 * coordinates, backtick escaping and the MYSQL_5 grammar remain intact.
 * Assumes the default MySQL SQL mode, without ANSI_QUOTES or NO_BACKSLASH_ESCAPES.
 */
public final class MySqlParserFactory {
    public static final SqlParserImplFactory FACTORY = Parser::new;

    private MySqlParserFactory() {}

    private static final class Parser extends SqlParserImpl {
        private LexicalState lexicalState;

        Parser(Reader reader) {
            super(new StringReader(""));
            ReInit(reader);
        }

        @Override
        public void switchTo(LexicalState state) {
            lexicalState = state;
            super.switchTo(state);
        }

        @Override
        public void ReInit(Reader reader) {
            String sql;
            try {
                StringBuilder text = new StringBuilder();
                char[] buffer = new char[4096];
                int count;
                while ((count = reader.read(buffer)) != -1) text.append(buffer, 0, count);
                sql = text.toString();
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot read MySQL SQL", e);
            }
            MaskedSql masked = mask(sql);
            SimpleCharStream stream = new SimpleCharStream(new StringReader(masked.text())) {
                { setTabSize(1); }
            };
            super.ReInit(new SqlParserImplTokenManager(stream) {
                @Override
                public Token getNextToken() {
                    Token token = super.getNextToken();
                    if (token.kind == SqlParserImplConstants.QUOTED_STRING
                        || token.kind == SqlParserImplConstants.PREFIXED_STRING_LITERAL) {
                        int offset = masked.lineStarts().get(token.beginLine - 1) + token.beginColumn - 1;
                        String prefix = token.image.substring(0, token.image.indexOf('\''));
                        offset += prefix.length();
                        int end = masked.lineStarts().get(token.endLine - 1) + token.endColumn - 1;
                        StringBuilder value = new StringBuilder();
                        boolean hasDoubleQuoted = false;
                        // Replacing delimiters can join adjacent mixed literals:
                        // "a"'b' becomes 'x''b'. Restore the complete token, not
                        // just the first fragment (which would silently lose b).
                        for (int cursor = offset; cursor <= end;) {
                            QuotedValue fragment = masked.values().get(cursor);
                            if (fragment == null) break;
                            value.append(fragment.value());
                            hasDoubleQuoted |= fragment.doubleQuoted();
                            cursor = fragment.end();
                        }
                        if (hasDoubleQuoted) token.image = prefix + "'" + value.toString().replace("'", "''") + "'";
                    }
                    return token;
                }
            });
            if (lexicalState != null) super.switchTo(lexicalState);
            setOriginalSql(sql);
        }
    }

    private record QuotedValue(int end, String value, boolean doubleQuoted) {}
    private record MaskedSql(String text, Map<Integer, QuotedValue> values, List<Integer> lineStarts) {}

    private static MaskedSql mask(String sql) {
        char[] text = sql.toCharArray();
        Map<Integer, QuotedValue> values = new HashMap<>();
        List<Integer> lineStarts = new ArrayList<>();
        lineStarts.add(0);
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '\n' || (c == '\r' && (i + 1 == sql.length() || sql.charAt(i + 1) != '\n'))) {
                lineStarts.add(i + 1);
            }
        }
        for (int i = 0; i < text.length;) {
            char c = sql.charAt(i);
            if (c == '-' && i + 1 < text.length && sql.charAt(i + 1) == '-') {
                while (i < text.length && sql.charAt(i) != '\n' && sql.charAt(i) != '\r') i++;
            } else if (c == '/' && i + 1 < text.length && sql.charAt(i + 1) == '*') {
                int end = sql.indexOf("*/", i + 2);
                i = end < 0 ? text.length : end + 2;
            } else if (c == '\'' || c == '`' || c == '"') {
                int start = i++;
                StringBuilder value = new StringBuilder();
                boolean closed = false;
                while (i < text.length) {
                    char next = sql.charAt(i++);
                    if (next == '\\' && c != '`' && i < text.length) {
                        char escaped = sql.charAt(i++);
                        value.append(switch (escaped) {
                            case '0' -> '\0';
                            case 'b' -> '\b';
                            case 'n' -> '\n';
                            case 'r' -> '\r';
                            case 't' -> '\t';
                            case 'Z' -> '\u001a';
                            // MySQL retains these escapes for LIKE patterns.
                            case '%', '_' -> "\\" + escaped;
                            default -> String.valueOf(escaped);
                        });
                    } else if (next == c) {
                        if (i < text.length && sql.charAt(i) == c) {
                            value.append(c);
                            i++;
                        } else {
                            closed = true;
                            break;
                        }
                    } else {
                        value.append(next);
                    }
                }
                if (c == '"' && closed) {
                    text[start] = '\'';
                    text[i - 1] = '\'';
                    for (int j = start + 1; j < i - 1; j++) {
                        if (text[j] != '\n' && text[j] != '\r') text[j] = 'x';
                    }
                }
                if (c != '`' && closed) values.put(start, new QuotedValue(i, value.toString(), c == '"'));
            } else {
                i++;
            }
        }
        return new MaskedSql(new String(text), values, lineStarts);
    }
}
