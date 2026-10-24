package com.bitdata.sqlworkbench.sql;

import org.apache.calcite.sql.SqlDialect;
import org.apache.calcite.sql.parser.SqlParserImplFactory;
import org.apache.calcite.sql.parser.impl.SqlParserImpl;
import org.apache.calcite.sql.validate.SqlConformance;

import java.util.Set;

/**
 * Immutable dialect definition holding Calcite SqlDialect, quote character,
 * reserved keywords, case sensitivity, SQL conformance, and parser factory.
 *
 * <p>Each {@link com.bitdata.sqlworkbench.connection.DatabaseStrategy} impl
 * exposes its own profile via {@code getDialectProfile()}.
 *
 * <p>{@code conformance} is the Calcite {@link SqlConformance} that the parser
 * should run under — controls dialect-specific syntax acceptance. Without it,
 * Calcite defaults to {@code SqlConformanceEnum.DEFAULT}, which rejects PG
 * {@code expr::type} casts, SqlServer {@code [bracket]} identifiers, Oracle
 * {@code FROM dual}, etc. (live-channel-assumption-sweep 2026-09-09 bug ①).
 *
 * <p>{@code parserFactory} controls the Calcite parser grammar backend. Most
 * dialects use the default {@link SqlParserImpl#FACTORY}, but PG routes through
 * {@code SqlBabelParserImpl.FACTORY} (CALCITE-2843) so the Babel grammar
 * accepts the PG {@code ::} postfix cast operator. Default null-safe via the
 * compact constructor.
 */
public record DialectProfile(
    String name,
    SqlDialect calciteDialect,
    char quoteChar,
    Set<String> reservedKeywords,
    boolean caseSensitive,
    SqlConformance conformance,
    SqlParserImplFactory parserFactory
) {
    public DialectProfile {
        if (name == null) {
            throw new IllegalArgumentException("name is required");
        }
        if (calciteDialect == null) {
            throw new IllegalArgumentException("calciteDialect is required");
        }
        if (reservedKeywords == null) {
            throw new IllegalArgumentException("reservedKeywords is required");
        }
        if (conformance == null) {
            throw new IllegalArgumentException("conformance is required");
        }
        if (parserFactory == null) {
            parserFactory = SqlParserImpl.FACTORY;
        }
    }
}
