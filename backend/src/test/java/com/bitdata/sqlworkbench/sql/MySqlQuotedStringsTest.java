package com.bitdata.sqlworkbench.sql;

import com.bitdata.sqlworkbench.sql.decompose.RuleTestDeps;
import org.apache.calcite.sql.*;
import org.apache.calcite.sql.parser.SqlParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MySqlQuotedStringsTest {
    private final SqlDialectResolver resolver = RuleTestDeps.testResolver();

    @Test
    void parsesReportedCteQuery() throws Exception {
        String sql = """
            WITH v1 AS(
            SELECT e.emp_no, e.last_name, e.first_name, e.gender, s.salary
            FROM employees e
            INNER JOIN salaries s ON e.emp_no=S.emp_no
            WHERE gender='M'
            ),
            v2 AS(
            SELECT d.dept_no, d.dept_name,m.emp_no
            FROM departments d
            INNER JOIN dept_manager m ON m.dept_no=d.dept_no
            ),
            v3 AS(
            SELECT e.last_name, e.first_name,dept_name,dept_no
            FROM v2
            INNER JOIN employees e ON e.emp_no=v2.emp_no
            WHERE e.gender='F'
            )
            SELECT v1.last_name,v1.first_name,v3.dept_name,v3.last_name AS "manager_last_name",v3.first_name AS "manager_first_name"
            FROM v1
            INNER JOIN dept_emp de ON de.emp_no=v1.emp_no
            INNER JOIN v3 ON v3.dept_no=de.dept_no
            """;
        SqlWith result = (SqlWith) new SqlParseEngine(resolver).parse(sql, "mysql");
        SqlSelect body = (SqlSelect) result.body;
        SqlIdentifier alias = ((SqlCall) body.getSelectList().get(3)).operand(1);
        assertEquals("manager_last_name", alias.getSimple());
        assertEquals(18, alias.getParserPosition().getLineNum());
        assertEquals(64, alias.getParserPosition().getColumnNum());
        assertDoesNotThrow(() -> SqlParser.create(sql, DialectParserConfigs.forDialect("mysql")).parseQuery());
    }

    @Test
    void doubleQuotesRemainStringLiteralsAndBackticksRemainIdentifiers() throws Exception {
        SqlSelect select = parse("SELECT \"hello\" AS \"greeting\", `a``b`, 'unchanged' FROM `t` WHERE x=\"F\"");
        SqlCall as = (SqlCall) select.getSelectList().get(0);
        assertEquals("hello", ((SqlLiteral) as.operand(0)).getValueAs(String.class));
        assertEquals("greeting", ((SqlIdentifier) as.operand(1)).getSimple());
        assertEquals("a`b", ((SqlIdentifier) select.getSelectList().get(1)).getSimple());
    }

    @Test
    void handlesEscapesAndDoesNotRewriteOtherQuotedRegionsOrComments() throws Exception {
        SqlSelect select = parse("SELECT \"a' b\"\"c\\\"d\" AS \"a`b\", '\"literal\"', `a\"b` /* \"comment\" */ -- \"comment\"\nFROM t");
        SqlCall as = (SqlCall) select.getSelectList().get(0);
        assertEquals("a' b\"c\"d", ((SqlLiteral) as.operand(0)).getValueAs(String.class));
        assertEquals("a`b", ((SqlIdentifier) as.operand(1)).getSimple());
        assertEquals("\"literal\"", ((SqlLiteral) select.getSelectList().get(1)).getValueAs(String.class));
        assertEquals("a\"b", ((SqlIdentifier) select.getSelectList().get(2)).getSimple());
    }

    @Test
    void otherDialectsStillTreatDoubleQuotesAsIdentifiers() throws Exception {
        SqlSelect pg = (SqlSelect) SqlParser.create("SELECT \"column_name\" FROM t", resolver.parserConfig("postgresql")).parseQuery();
        assertInstanceOf(SqlIdentifier.class, pg.getSelectList().get(0));
        assertThrows(Exception.class, () -> parse("SELECT x AS \"unterminated"));
        assertThrows(Exception.class, () -> parse("SELECT n::int FROM t"));
    }

    private SqlSelect parse(String sql) throws Exception {
        return (SqlSelect) SqlParser.create(sql, resolver.parserConfig("mysql")).parseQuery();
    }

    @Test
    void sharedConsumersAcceptAliasesAndGuardStillRejectsMultipleStatements() throws Exception {
        String sql = "SELECT x AS \"alias\" FROM t WHERE y=\"F\"";
        assertDoesNotThrow(() -> new SelectOnlyGuard(resolver).requireSingleSelect(sql, "mysql"));
        assertFalse(new SqlPositionService(resolver).extractPositions(sql, "mysql").isEmpty());
        assertFalse(com.bitdata.sqlworkbench.sql.diff.AstSerializer.serialize(sql, "mysql").isEmpty());
        assertThrows(IllegalArgumentException.class, () -> new SelectOnlyGuard(resolver)
            .requireSingleSelect("SELECT \"; DROP TABLE t\"; DELETE FROM t", "mysql"));
    }

    @Test
    void preservesPositionsAcrossTabsCrLfMultilineAndEscapedStrings() throws Exception {
        String sql = "SELECT\t\"a'b\" AS \"alias\",\r\n\t\"line1\r\nline2\" AS \"second\" FROM t";
        SqlSelect select = parse(sql);
        SqlIdentifier first = ((SqlCall) select.getSelectList().get(0)).operand(1);
        assertEquals(sql.indexOf("\"alias\"") + 1, first.getParserPosition().getColumnNum());
        SqlCall second = (SqlCall) select.getSelectList().get(1);
        assertEquals("line1\r\nline2", ((SqlLiteral) second.operand(0)).getValueAs(String.class));
        assertEquals(3, second.<SqlIdentifier>operand(1).getParserPosition().getLineNum());
        var error = assertThrows(org.apache.calcite.sql.parser.SqlParseException.class,
            () -> parse("SELECT \"a'b\" AS \"alias\", FROM t"));
        assertEquals(26, error.getPos().getColumnNum());
    }

    @Test
    void parserCanBeReusedWithNewSql() throws Exception {
        SqlParser parser = SqlParser.create("SELECT \"first\"", resolver.parserConfig("mysql"));
        parser.parseQuery();
        SqlSelect second = (SqlSelect) parser.parseQuery("SELECT `x` AS \"second\" FROM `t`");
        assertEquals("second", ((SqlCall) second.getSelectList().get(0)).<SqlIdentifier>operand(1).getSimple());
    }

    @Test
    void adjacentMixedQuoteLiteralsDoNotLoseContent() throws Exception {
        SqlSelect select = parse("SELECT \"a\"'b', 'c'\"d\", \"e\"'f'\"g\"");
        assertEquals("ab", ((SqlLiteral) select.getSelectList().get(0)).getValueAs(String.class));
        assertEquals("cd", ((SqlLiteral) select.getSelectList().get(1)).getValueAs(String.class));
        assertEquals("efg", ((SqlLiteral) select.getSelectList().get(2)).getValueAs(String.class));
        SqlSelect prefixed = parse("SELECT N\"hello\", \"\"");
        assertEquals("hello", ((SqlLiteral) prefixed.getSelectList().get(0)).getValueAs(String.class));
        assertEquals("", ((SqlLiteral) prefixed.getSelectList().get(1)).getValueAs(String.class));
    }
}
