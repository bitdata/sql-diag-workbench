package com.bitdata.sqlworkbench.connection;

import com.bitdata.sqlworkbench.sql.DialectProfile;
import org.apache.calcite.sql.dialect.OracleSqlDialect;
import org.apache.calcite.sql.parser.impl.SqlParserImpl;
import org.apache.calcite.sql.validate.SqlConformanceEnum;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

@Component
public class OracleStrategy implements DatabaseStrategy {

    @Override
    public String getJdbcUrl(String host, int port, String database) {
        return getJdbcUrl(host, port, database, "SERVICE_NAME");
    }

    public String getJdbcUrl(String host, int port, String database, String connectionType) {
        JdbcUrlInputs.validate(host, port, database);
        if ("SID".equals(connectionType)) {
            return String.format("jdbc:oracle:thin:@%s:%d:%s", host, port, database);
        }
        // Default: SERVICE_NAME (also covers null)
        return String.format("jdbc:oracle:thin:@//%s:%d/%s", host, port, database);
    }

    @Override
    public String getExplainSql(String sql) {
        return "EXPLAIN PLAN FOR " + sql;
    }

    @Override
    public String getAnalyzeSql(String sql) {
        // Oracle EXPLAIN PLAN FOR does not have a separate ANALYZE mode
        return "EXPLAIN PLAN FOR " + sql;
    }

    @Override
    public boolean supportsReadOnly() {
        return true;
    }

    @Override
    public void configureReadOnly(Connection conn) throws SQLException {
        // Oracle has no session-level READ ONLY (ORA-02248 on ALTER SESSION
        // SET READ ONLY). Rely on JDBC-level conn.setReadOnly(true), which
        // ojdbc honors via the connection's transaction-read-only flag.
        conn.setReadOnly(true);
    }

    @Override
    public String dialect() {
        return "oracle";
    }

    @Override
    public DialectProfile getDialectProfile() {
        return new DialectProfile(
            "oracle",
            OracleSqlDialect.DEFAULT,
            '"',
            SqlKeywords.forDialect("oracle"),
            true,
            SqlConformanceEnum.ORACLE_10,
            SqlParserImpl.FACTORY  // default — explicit for readability (A2 narrow-surface: only PG gets babel)
        );
    }

    @Override
    public String wrapVerificationSql(String innerSelect) {
        // Oracle: no LIMIT, use FETCH FIRST; derived-table aliases take no AS.
        return "SELECT COUNT(*) FROM (SELECT 1 FROM (" + innerSelect + ") _inner FETCH FIRST 1 ROWS ONLY) _verify";
    }
}