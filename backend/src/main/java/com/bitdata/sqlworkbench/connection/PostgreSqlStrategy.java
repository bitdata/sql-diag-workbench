package com.bitdata.sqlworkbench.connection;

import com.bitdata.sqlworkbench.sql.DialectProfile;
import org.apache.calcite.sql.dialect.PostgresqlSqlDialect;
import org.apache.calcite.sql.parser.babel.SqlBabelParserImpl;
import org.apache.calcite.sql.validate.SqlConformanceEnum;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

@Component
public class PostgreSqlStrategy implements DatabaseStrategy {

    @Override
    public String getJdbcUrl(String host, int port, String database) {
        JdbcUrlInputs.validate(host, port, database);
        return String.format("jdbc:postgresql://%s:%d/%s", host, port, database);
    }

    @Override
    public String getExplainSql(String sql) {
        return "EXPLAIN (ANALYZE false, FORMAT JSON) " + sql;
    }

    @Override
    public String getAnalyzeSql(String sql) {
        return "EXPLAIN (ANALYZE true, FORMAT JSON) " + sql;
    }

    @Override
    public boolean supportsReadOnly() {
        return true;
    }

    @Override
    public void configureReadOnly(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("SET default_transaction_read_only = on");
        }
        conn.setReadOnly(true);
    }

    @Override
    public String dialect() {
        return "postgresql";
    }

    @Override
    public DialectProfile getDialectProfile() {
        return new DialectProfile(
            "postgresql",
            PostgresqlSqlDialect.DEFAULT,
            '"',
            SqlKeywords.forDialect("postgresql"),
            true,
            // Calcite 1.37 has no dedicated POSTGRESQL enum. BABEL is the
            // most permissive conformance and is the de-facto PG choice —
            // it enables `::` casts and other PG-specific syntax that DEFAULT
            // rejects (live-channel-assumption-sweep 2026-09-09 bug ①).
            SqlConformanceEnum.BABEL,
            // Babel parser factory (CALCITE-2843) accepts PG `::` postfix
            // cast. Default factory rejects it even with BABEL conformance
            // (recon probe A8, pg-cast-babel-and-ticket-sweep PR-1).
            SqlBabelParserImpl.FACTORY
        );
    }

    @Override
    public String wrapVerificationSql(String innerSelect) {
        return "SELECT COUNT(*) FROM (SELECT 1 FROM (" + innerSelect + ") AS _inner LIMIT 1) AS _verify";
    }
}