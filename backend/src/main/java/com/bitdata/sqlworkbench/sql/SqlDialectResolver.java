package com.bitdata.sqlworkbench.sql;

import com.bitdata.sqlworkbench.connection.DatabaseStrategyFactory;
import org.apache.calcite.avatica.util.Quoting;
import org.apache.calcite.avatica.util.Casing;
import org.apache.calcite.sql.SqlDialect;
import org.apache.calcite.sql.parser.SqlParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Resolves SQL dialect string to Calcite SqlDialect and provides dialect-specific settings.
 *
 * <p>Delegates to {@link DatabaseStrategyFactory} as the single source of truth
 * for dialect definitions. Each strategy exposes its own {@link DialectProfile}
 * via {@code getDialectProfile()}.
 */
@Component
public class SqlDialectResolver {

    private static final Logger log = LoggerFactory.getLogger(SqlDialectResolver.class);

    private final DatabaseStrategyFactory strategyFactory;

    public SqlDialectResolver(DatabaseStrategyFactory strategyFactory) {
        this.strategyFactory = strategyFactory;
    }

    /**
     * Returns the parsing profile for the given dialect name.
     *
     * <p>Falls back to PostgreSQL when the dialect is {@code null} or unknown.
     * The fallback is observable: an SLF4J WARN is logged at the point of
     * fallback with the original dialect name, satisfying rule #2
     * ("Observable degradation"). Callers that need to distinguish a real
     * PostgreSQL request from a fallback should use
     * {@link DatabaseStrategyFactory#get(String)} directly and handle
     * {@link IllegalArgumentException} themselves.
     */
    public DialectProfile getProfile(String dialectName) {
        try {
            return strategyFactory.get(dialectName).getDialectProfile();
        } catch (IllegalArgumentException e) {
            log.warn("Unknown or null dialect '{}' — falling back to postgresql (rule #2: " +
                "degradation must be observable). Add a DatabaseStrategy bean + register it in " +
                "BuiltInStrategies.ALL to remove this fallback.", dialectName);
            return strategyFactory.get("postgresql").getDialectProfile();
        }
    }

    public SqlDialect getDialect(String name) {
        return getProfile(name).calciteDialect();
    }

    public boolean isCaseSensitive(String name) {
        return getProfile(name).caseSensitive();
    }

    /**
     * 该方言是否已在 connection 层注册。null 视为支持（走默认方言）。
     * 解析层白名单以此为准，避免与 {@code DatabaseStrategyFactory} 漂移（P2-12c）。
     */
    public boolean isSupported(String name) {
        if (name == null) {
            return true;
        }
        try {
            strategyFactory.get(name);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public SqlParser.Config parserConfig(String name) {
        DialectProfile profile = getProfile(name);
        return SqlParser.config()
            // MySQL table/alias spelling can be case-sensitive on the server.
            // Folding here corrupts executable fragments on lower_case_table_names=0.
            .withUnquotedCasing("mysql".equalsIgnoreCase(name) ? Casing.UNCHANGED : Casing.TO_UPPER)
            .withCaseSensitive(profile.caseSensitive())
            .withQuoting(quotingFor(profile.quoteChar()))
            .withConformance(profile.conformance())
            .withParserFactory(profile.parserFactory());
    }

    private static Quoting quotingFor(char quoteChar) {
        return switch (quoteChar) {
            case '`' -> Quoting.BACK_TICK;
            case '[' -> Quoting.BRACKET;
            default -> Quoting.DOUBLE_QUOTE;
        };
    }
}
