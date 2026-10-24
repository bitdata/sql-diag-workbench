package com.bitdata.sqlworkbench.connection;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JdbcUrlInputsTest {

    @Test
    void acceptsNormalValues() {
        assertDoesNotThrow(() -> JdbcUrlInputs.validate("localhost", 5432, "employees"));
        assertDoesNotThrow(() -> JdbcUrlInputs.validate("192.168.0.1", 1433, "AdventureWorks"));
        assertDoesNotThrow(() -> JdbcUrlInputs.validate("db-server_01.example.com", 1, "my_db.v2"));
        assertDoesNotThrow(() -> JdbcUrlInputs.validate("localhost", 65535, "db"));
    }

    @Test
    void rejectsHostWithUrlPropertySeparators() {
        for (String host : new String[]{
                "evil;user=admin", "evil?ssl=true", "evil&x=1", "evil=1",
                "evil/otherdb", "evil:5432", "evil host", ""}) {
            assertThrows(IllegalArgumentException.class,
                () -> JdbcUrlInputs.validate(host, 5432, "db"), "host=" + host);
        }
        assertThrows(IllegalArgumentException.class, () -> JdbcUrlInputs.validate(null, 5432, "db"));
    }

    @Test
    void rejectsPortOutOfRange() {
        assertThrows(IllegalArgumentException.class, () -> JdbcUrlInputs.validate("localhost", 0, "db"));
        assertThrows(IllegalArgumentException.class, () -> JdbcUrlInputs.validate("localhost", -1, "db"));
        assertThrows(IllegalArgumentException.class, () -> JdbcUrlInputs.validate("localhost", 65536, "db"));
    }

    @Test
    void rejectsDatabaseOutsideWhitelist() {
        for (String database : new String[]{"db;DROP", "db?x", "db/other", "db name", ""}) {
            assertThrows(IllegalArgumentException.class,
                () -> JdbcUrlInputs.validate("localhost", 5432, database), "database=" + database);
        }
        assertThrows(IllegalArgumentException.class, () -> JdbcUrlInputs.validate("localhost", 5432, null));
    }

    @Test
    void everyStrategyRejectsInjectedHostAtGetJdbcUrlEntry() {
        for (DatabaseStrategy strategy : new DatabaseStrategy[]{
                new PostgreSqlStrategy(), new MySqlStrategy(),
                new SqlServerStrategy(), new OracleStrategy()}) {
            assertThrows(IllegalArgumentException.class,
                () -> strategy.getJdbcUrl("evil;user=admin", 5432, "db"),
                strategy.dialect());
        }
    }
}
