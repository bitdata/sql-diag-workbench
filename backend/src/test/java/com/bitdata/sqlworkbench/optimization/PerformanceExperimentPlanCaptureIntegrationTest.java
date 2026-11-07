package com.bitdata.sqlworkbench.optimization;

import com.bitdata.sqlworkbench.connection.DatabaseStrategyFactory;
import com.bitdata.sqlworkbench.execution.ExplainServiceFactory;
import com.bitdata.sqlworkbench.execution.MySqlPlanAdapter;
import com.bitdata.sqlworkbench.execution.PostgreSqlPlanAdapter;
import com.bitdata.sqlworkbench.testsupport.Dialect;
import com.bitdata.sqlworkbench.testsupport.RequiresTestDatabase;
import com.bitdata.sqlworkbench.testsupport.TestDatabaseResolver;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
class PerformanceExperimentPlanCaptureIntegrationTest {
    private final PerformanceExperimentPlanCapture capture = new PerformanceExperimentPlanCapture(
        DatabaseStrategyFactory.withBuiltinStrategies(),
        new ExplainServiceFactory(List.of(new PostgreSqlPlanAdapter(), new MySqlPlanAdapter())));

    @Test
    @RequiresTestDatabase(Dialect.POSTGRESQL)
    void capturesPostgresJsonPlansOnTheExperimentSession() throws Exception {
        var entry = TestDatabaseResolver.resolve(Dialect.POSTGRESQL);
        try (Connection connection = DriverManager.getConnection(
                TestDatabaseResolver.buildJdbcUrl(Dialect.POSTGRESQL, entry), entry.username(), entry.password())) {
            prepareReadOnlySnapshot(connection, false);
            String identity = scalar(connection, "SELECT pg_backend_pid()");
            String sql = "SELECT COUNT(*) AS n FROM departments";
            var plans = capture.capture(connection, "postgresql", sql, sql, "revision-pg", new ValidationControl(60));
            assertEquals("POSTGRESQL_JSON", plans.original().channel());
            assertEquals("revision-pg", plans.original().connectionRevision());
            assertEquals(identity, plans.original().sessionIdentity());
            assertEquals(sql, plans.original().submittedSql());
            assertNotEquals("FAILED", plans.original().status(), plans.original().errorReason());
            assertNotNull(plans.original().details());
        }
    }

    @Test
    @RequiresTestDatabase(Dialect.MYSQL)
    void capturesMysqlTreePlansAndKeepsOneSideWhenTheOtherCaptureFails() throws Exception {
        var entry = TestDatabaseResolver.resolve(Dialect.MYSQL);
        try (Connection connection = DriverManager.getConnection(
                TestDatabaseResolver.buildJdbcUrl(Dialect.MYSQL, entry), entry.username(), entry.password())) {
            prepareReadOnlySnapshot(connection, true);
            String identity = scalar(connection, "SELECT CONNECTION_ID()");
            String sql = "SELECT COUNT(*) AS n FROM departments";
            var plans = capture.capture(connection, "mysql", sql, "SELECT FROM", "revision-mysql", new ValidationControl(60));
            assertEquals("MYSQL_TREE", plans.original().channel());
            assertEquals("revision-mysql", plans.original().connectionRevision());
            assertEquals(identity, plans.original().sessionIdentity());
            assertEquals(sql, plans.original().submittedSql());
            assertNotEquals("FAILED", plans.original().status(), plans.original().errorReason());
            assertEquals("FAILED", plans.candidate().status());
            assertEquals("ACTUAL_PLAN_CAPTURE_FAILED", plans.candidate().errorReason());
            assertTrue(plans.degraded());
        }
    }

    private static void prepareReadOnlySnapshot(Connection connection, boolean mysql) throws Exception {
        if (mysql) try (Statement statement = connection.createStatement()) {
            statement.execute("SET SESSION TRANSACTION READ ONLY");
        }
        connection.setReadOnly(true);
        connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
        connection.setAutoCommit(false);
    }

    private static String scalar(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getString(1);
        }
    }
}
