package com.bitdata.sqlworkbench.sql;

import com.bitdata.sqlworkbench.connection.DatabaseStrategyFactory;
import com.bitdata.sqlworkbench.sql.diff.SqlCanonicalizer;
import org.apache.calcite.sql.SqlNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class NodeToSqlBetweenTest {
    final SqlDialectResolver resolver = new SqlDialectResolver(DatabaseStrategyFactory.withBuiltinStrategies());
    final SqlParseEngine parser = new SqlParseEngine(resolver);

    @ParameterizedTest
    @ValueSource(strings = {
        "SELECT x FROM t WHERE x BETWEEN 1 AND 3",
        "SELECT x FROM t WHERE x NOT BETWEEN 1 AND 3",
        "SELECT x FROM t WHERE NOT (x BETWEEN 1 AND 3) OR x BETWEEN 5 AND 7",
        "SELECT x FROM t WHERE x BETWEEN (1 + 2) AND (3 * 4) AND y = 1",
        "SELECT CASE WHEN x BETWEEN 1 AND 3 THEN NULL ELSE x END FROM t",
        "SELECT x BETWEEN (y AND z) AND (a OR b) FROM t",
        "SELECT x FROM t WHERE x BETWEEN ASYMMETRIC NULL AND 3"
    })
    void mysqlProducesExecutableSpellingWithoutChangingIdentityOrAst(String source) throws Exception {
        SqlNode ast = parser.parse(source,"mysql");
        String before = ast.toSqlString(resolver.getDialect("mysql")).getSql();
        String output = NodeToSql.toSql(ast,resolver.getProfile("mysql"));
        assertFalse(output.contains("ASYMMETRIC"),output);
        assertEquals(SqlCanonicalizer.canonicalizeSql(source,"mysql"),SqlCanonicalizer.canonicalizeSql(output,"mysql"));
        assertEquals(before,ast.toSqlString(resolver.getDialect("mysql")).getSql());
    }

    @Test void postgresRetainsSymmetricMeaning() throws Exception {
        String source="SELECT 2 BETWEEN SYMMETRIC 3 AND 1 AS p";
        String output=NodeToSql.toSql(parser.parse(source,"postgresql"),resolver.getProfile("postgresql"));
        assertTrue(output.contains("SYMMETRIC"));
        assertEquals(SqlCanonicalizer.canonicalizeSql(source,"postgresql"),SqlCanonicalizer.canonicalizeSql(output,"postgresql"));
        assertNotEquals(SqlCanonicalizer.canonicalizeSql(source,"postgresql"),SqlCanonicalizer.canonicalizeSql("SELECT 2 BETWEEN 3 AND 1 AS p","postgresql"));
    }

    @Test void mysqlSymmetricCannotBeMistakenForAnExecutableAsymmetricPredicate() throws Exception {
        var ast=parser.parse("SELECT x NOT BETWEEN SYMMETRIC 3 AND 1 FROM t","mysql");
        assertThrows(IllegalArgumentException.class,()->NodeToSql.toSql(ast,resolver.getProfile("mysql")));
    }

    @Test void literalAndQuotedIdentifierAreNotRewritten() throws Exception {
        String source="SELECT 'BETWEEN ASYMMETRIC', `BETWEEN ASYMMETRIC` FROM t WHERE x BETWEEN 1 AND 3";
        String output=NodeToSql.toSql(parser.parse(source,"mysql"),resolver.getProfile("mysql"));
        assertTrue(output.contains("'BETWEEN ASYMMETRIC'"));
        assertTrue(output.contains("`BETWEEN ASYMMETRIC`"));
        assertFalse(output.contains("x BETWEEN ASYMMETRIC"));
    }

    @ParameterizedTest @ValueSource(strings={"SELECT id FROM t LIMIT 3 OFFSET 1","SELECT FLOOR(x) FROM t","SELECT CAST(x AS CHAR(10)) FROM t"})
    void nonBetweenQuotedOutputStillUsesMysqlDialect(String source) throws Exception {
        var ast=parser.parse(source,"mysql");
        assertEquals(ast.toSqlString(resolver.getDialect("mysql")).getSql(),NodeToSql.toQuotedSql(ast,resolver.getProfile("mysql")));
    }

    @Test void operandsAreEmittedOnceAndQuotedOutputKeepsIdentifierSpelling() throws Exception {
        String source="SELECT RAND() BETWEEN ABS(-1) AND CEIL(3.1) AS `MixedCase`";
        String output=NodeToSql.toQuotedSql(parser.parse(source,"mysql"),resolver.getProfile("mysql"));
        for(String function:new String[]{"RAND(","ABS(","CEIL("})
            assertEquals(1,output.split(java.util.regex.Pattern.quote(function),-1).length-1,output);
        assertTrue(output.contains("`MixedCase`"),output);
    }
}
