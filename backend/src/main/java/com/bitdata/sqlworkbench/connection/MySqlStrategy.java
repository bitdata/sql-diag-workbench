package com.bitdata.sqlworkbench.connection;

import com.bitdata.sqlworkbench.sql.DialectProfile;
import com.bitdata.sqlworkbench.sql.MySqlParserFactory;
import org.apache.calcite.sql.dialect.MysqlSqlDialect;
import org.apache.calcite.sql.validate.SqlConformanceEnum;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

@Component
public class MySqlStrategy implements DatabaseStrategy {

    @Override
    public String getJdbcUrl(String host, int port, String database) {
        JdbcUrlInputs.validate(host, port, database);
        return String.format("jdbc:mysql://%s:%d/%s?useSSL=false&allowPublicKeyRetrieval=true", host, port, database);
    }

    @Override
    public String getExplainSql(String sql) {
        return "EXPLAIN FORMAT=JSON " + sql;
    }

    @Override
    public String getAnalyzeSql(String sql) {
        return "EXPLAIN ANALYZE FORMAT=JSON " + sql;
    }

    @Override
    public boolean supportsReadOnly() {
        return true;
    }

    @Override
    public void configureReadOnly(Connection conn) throws SQLException {
        // Defense in depth behind the SelectOnlyGuard: even a SQL that slips
        // past the whitelist cannot mutate data on MySQL.
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("SET SESSION TRANSACTION READ ONLY");
        }
        conn.setReadOnly(true);
    }

    @Override
    public String dialect() {
        return "mysql";
    }

    @Override
    public DialectProfile getDialectProfile() {
        return new DialectProfile(
            "mysql",
            MysqlSqlDialect.DEFAULT,
            '`',
            SqlKeywords.forDialect("mysql"),
            false,
            SqlConformanceEnum.MYSQL_5,
            MySqlParserFactory.FACTORY // MYSQL_5 grammar with double-quoted string tokens
        );
    }

    @Override
    public String wrapVerificationSql(String innerSelect) {
        return "SELECT COUNT(*) FROM (SELECT 1 FROM (" + innerSelect + ") AS _inner LIMIT 1) AS _verify";
    }
}
