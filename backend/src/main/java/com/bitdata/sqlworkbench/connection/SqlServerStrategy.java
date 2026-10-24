package com.bitdata.sqlworkbench.connection;

import com.bitdata.sqlworkbench.sql.DialectProfile;
import org.apache.calcite.sql.dialect.MssqlSqlDialect;
import org.apache.calcite.sql.parser.impl.SqlParserImpl;
import org.apache.calcite.sql.validate.SqlConformanceEnum;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.SQLException;

@Component
public class SqlServerStrategy implements DatabaseStrategy {

    @Override
    public String getJdbcUrl(String host, int port, String database) {
        return getJdbcUrl(host, port, database, false);
    }

    /**
     * S6 — trustServerCertificate is a per-connection opt-in, default false.
     * Enabling it disables TLS certificate validation; only for servers whose
     * certificate cannot be validated the normal way.
     */
    public String getJdbcUrl(String host, int port, String database, boolean trustServerCertificate) {
        JdbcUrlInputs.validate(host, port, database);
        String url = String.format("jdbc:sqlserver://%s:%d;databaseName=%s", host, port, database);
        return trustServerCertificate ? url + ";trustServerCertificate=true" : url;
    }

    @Override
    public String getExplainSql(String sql) {
        // SET SHOWPLAN_TEXT must run as a stand-alone batch on SQL Server,
        // which JDBC executeQuery cannot do. Return the SQL as-is; the
        // plan-capture path is a separate concern (see: sp_executesql +
        // sys.dm_exec_query_plan, future work).
        return sql;
    }

    @Override
    public String getAnalyzeSql(String sql) {
        // SQL Server plan capture is a separate concern (future work)
        return sql;
    }

    @Override
    public boolean supportsReadOnly() {
        return true;
    }

    @Override
    public void configureReadOnly(Connection conn) throws SQLException {
        conn.setReadOnly(true);
    }

    @Override
    public String dialect() {
        return "sqlserver";
    }

    @Override
    public DialectProfile getDialectProfile() {
        return new DialectProfile(
            "sqlserver",
            MssqlSqlDialect.DEFAULT,
            // T-SQL uses `[bracket]` identifiers (also supports "double-quoted"
            // for SQL-92 compat, but brackets are canonical). The previous
            // value '"' was wrong — it prevented `SELECT [id] FROM [t]` from
            // parsing (live-channel-assumption-sweep 2026-09-09 bug ①,
            // SqlServer channels).
            '[',
            SqlKeywords.forDialect("sqlserver"),
            true,
            // Calcite 1.37's nearest SqlServer conformance is SQL_SERVER_2008
            // (no SQL_SERVER_2005 in this version). Enables T-SQL extensions.
            SqlConformanceEnum.SQL_SERVER_2008,
            SqlParserImpl.FACTORY  // default — explicit for readability (A2 narrow-surface: only PG gets babel)
        );
    }

    @Override
    public String wrapVerificationSql(String innerSelect) {
        return "SELECT COUNT(*) FROM (SELECT TOP 1 1 FROM (" + innerSelect + ") AS _inner) AS _verify";
    }
}