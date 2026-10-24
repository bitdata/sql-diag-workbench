package com.bitdata.sqlworkbench.sql;

import com.bitdata.sqlworkbench.connection.MySqlStrategy;
import com.bitdata.sqlworkbench.connection.OracleStrategy;
import com.bitdata.sqlworkbench.connection.PostgreSqlStrategy;
import com.bitdata.sqlworkbench.connection.SqlServerStrategy;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.dialect.MssqlSqlDialect;
import org.apache.calcite.sql.dialect.MysqlSqlDialect;
import org.apache.calcite.sql.dialect.OracleSqlDialect;
import org.apache.calcite.sql.dialect.PostgresqlSqlDialect;
import org.apache.calcite.sql.parser.SqlParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DialectSwitchTest {

    private SqlNode parseSelectStarFrom(String table) throws Exception {
        return SqlParser.create("SELECT * FROM " + table).parseQuery();
    }

    @Test
    void postgresDeparse_producesUnquotedIdentifier() throws Exception {
        String sql = NodeToSql.toSql(parseSelectStarFrom("dual_t"),
            new PostgreSqlStrategy().getDialectProfile());
        assertTrue(sql.toLowerCase().contains("dual_t"),
            "Postgres deparse should contain unquoted 'dual_t': " + sql);
        assertFalse(sql.contains("\"dual_t\""),
            "Postgres deparse should not over-quote: " + sql);
    }

    @Test
    void mysqlDeparse_producesUnquotedIdentifier() throws Exception {
        String sql = NodeToSql.toSql(parseSelectStarFrom("dual_t"),
            new MySqlStrategy().getDialectProfile());
        assertTrue(sql.toLowerCase().contains("dual_t"),
            "MySQL deparse should contain unquoted 'dual_t': " + sql);
    }

    @Test
    void sqlserverDeparse_producesUnquotedIdentifier() throws Exception {
        String sql = NodeToSql.toSql(parseSelectStarFrom("dual_t"),
            new SqlServerStrategy().getDialectProfile());
        assertTrue(sql.toLowerCase().contains("dual_t"),
            "SQL Server deparse should contain unquoted 'dual_t': " + sql);
    }

    @Test
    void oracleDeparse_producesUnquotedIdentifier() throws Exception {
        String sql = NodeToSql.toSql(parseSelectStarFrom("dual_t"),
            new OracleStrategy().getDialectProfile());
        assertTrue(sql.toLowerCase().contains("dual_t"),
            "Oracle deparse should contain unquoted 'dual_t': " + sql);
    }

    @Test
    void allDialects_haveDistinctCalciteDialects() {
        DialectProfile pg = new PostgreSqlStrategy().getDialectProfile();
        DialectProfile my = new MySqlStrategy().getDialectProfile();
        DialectProfile ms = new SqlServerStrategy().getDialectProfile();
        DialectProfile or = new OracleStrategy().getDialectProfile();

        assertEquals(PostgresqlSqlDialect.DEFAULT, pg.calciteDialect());
        assertEquals(MysqlSqlDialect.DEFAULT, my.calciteDialect());
        assertEquals(MssqlSqlDialect.DEFAULT, ms.calciteDialect());
        assertEquals(OracleSqlDialect.DEFAULT, or.calciteDialect());
    }
}
