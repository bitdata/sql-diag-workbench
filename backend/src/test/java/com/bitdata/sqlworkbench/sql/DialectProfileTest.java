package com.bitdata.sqlworkbench.sql;

import org.apache.calcite.sql.dialect.MysqlSqlDialect;
import org.apache.calcite.sql.dialect.PostgresqlSqlDialect;
import org.apache.calcite.sql.parser.impl.SqlParserImpl;
import org.apache.calcite.sql.validate.SqlConformanceEnum;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class DialectProfileTest {

    @Test
    void constructsValidProfile() {
        Set<String> reserved = Set.of("select", "from");
        DialectProfile profile = new DialectProfile(
            "postgresql",
            PostgresqlSqlDialect.DEFAULT,
            '"',
            reserved,
            true,
            SqlConformanceEnum.BABEL,
            SqlParserImpl.FACTORY
        );
        assertEquals("postgresql", profile.name());
        assertEquals(PostgresqlSqlDialect.DEFAULT, profile.calciteDialect());
        assertEquals('"', profile.quoteChar());
        assertEquals(reserved, profile.reservedKeywords());
        assertTrue(profile.caseSensitive());
        assertEquals(SqlConformanceEnum.BABEL, profile.conformance());
        assertEquals(SqlParserImpl.FACTORY, profile.parserFactory());
    }

    @Test
    void rejectsNullName() {
        assertThrows(IllegalArgumentException.class, () -> new DialectProfile(
            null, PostgresqlSqlDialect.DEFAULT, '"', Set.of(), true, SqlConformanceEnum.BABEL, SqlParserImpl.FACTORY));
    }

    @Test
    void rejectsNullCalciteDialect() {
        assertThrows(IllegalArgumentException.class, () -> new DialectProfile(
            "postgresql", null, '"', Set.of(), true, SqlConformanceEnum.BABEL, SqlParserImpl.FACTORY));
    }

    @Test
    void rejectsNullReservedKeywords() {
        assertThrows(IllegalArgumentException.class, () -> new DialectProfile(
            "postgresql", PostgresqlSqlDialect.DEFAULT, '"', null, true, SqlConformanceEnum.BABEL, SqlParserImpl.FACTORY));
    }

    @Test
    void rejectsNullConformance() {
        assertThrows(IllegalArgumentException.class, () -> new DialectProfile(
            "postgresql", PostgresqlSqlDialect.DEFAULT, '"', Set.of(), true, null, SqlParserImpl.FACTORY));
    }

    @Test
    void recordEqualityWorks() {
        DialectProfile a = new DialectProfile("mysql", MysqlSqlDialect.DEFAULT, '`', Set.of("select"), false, SqlConformanceEnum.MYSQL_5, SqlParserImpl.FACTORY);
        DialectProfile b = new DialectProfile("mysql", MysqlSqlDialect.DEFAULT, '`', Set.of("select"), false, SqlConformanceEnum.MYSQL_5, SqlParserImpl.FACTORY);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }
}
