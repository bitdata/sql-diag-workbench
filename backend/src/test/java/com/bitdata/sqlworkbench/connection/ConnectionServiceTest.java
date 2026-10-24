package com.bitdata.sqlworkbench.connection;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * DB-free contract tests for {@link ConnectionService}'s handshake deadline and failure
 * classification (spec §5.1 / §5.2). The JDBC handshake is a package-private seam
 * ({@link ConnectionService.JdbcConnector}) so the deadline is exercised with no driver and
 * no network; the real blackhole behaviour is pinned end-to-end by
 * {@code e2e/connection-timeout.spec.ts}, which is also what pins the PRODUCTION values of
 * {@link ConnectionService#TEST_CONNECT_TIMEOUT_MS} / {@link ConnectionService#WORKLOAD_CONNECT_TIMEOUT_MS}.
 */
class ConnectionServiceTest {

    private static final long DEADLINE_MS = 300L;

    /** The driver's localized failure text — must never reach the response body. */
    private static final String DRIVER_TEXT = "尝试连线已失败。";

    private static final String URL_HOST = "10.255.255.1";

    private static ConnectionService service(ConnectionService.JdbcConnector connector) {
        return new ConnectionService(mock(ConnectionStore.class), mock(KeychainStore.class),
            DatabaseStrategyFactory.withBuiltinStrategies(), connector, DEADLINE_MS, DEADLINE_MS);
    }

    /**
     * A handshake that parks (like a driver waiting on an unanswered SYN) and then throws the
     * driver's localized text. The interrupt is deliberately swallowed — see
     * {@code ConnectWatchdogTest#parkedHandshake}.
     */
    private static ConnectionService.JdbcConnector parked() {
        return (url, username, password) -> {
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException e) {
                // The deadline fired and cancelled us — fall through to the driver-style throw.
            }
            throw new SQLException(DRIVER_TEXT);
        };
    }

    private static ConnectionRequest request() {
        return new ConnectionRequest("Test", URL_HOST, 5432, "testdb", "user", "pw", "postgresql", null, null);
    }

    private static ConnectionConfig config() {
        return new ConnectionConfig("conn-1", "Test", URL_HOST, 5432, "testdb", "user",
            "postgresql", "keychain:conn-1", false, "2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z", null, false);
    }

    // ─── test(ConnectionRequest) — spec §5.1 ─────────────────────────────

    @Test
    void test_classifiesTheHandshakeDeadlineAsConnectTimeout() {
        Map<String, Object> result = service(parked()).test(request());

        assertEquals(Boolean.FALSE, result.get("success"));
        assertEquals("connect-timeout", result.get("errorReason"));
        String error = (String) result.get("error");
        assertTrue(error.contains("timed out"), "error must state the timeout, was: " + error);
        assertTrue(error.contains("300ms"), "error must carry the deadline value, was: " + error);
        assertFalse(error.contains(DRIVER_TEXT),
            "the driver's localized text must not leak on the deadline path, was: " + error);
        assertNotNull(result.get("latency"));
    }

    @Test
    void test_classifiesOtherFailuresAsOtherAndKeepsTheDriverDetail() {
        Map<String, Object> result = service((url, user, password) -> {
            throw new SQLException("FATAL: password authentication failed for user \"user\"");
        }).test(request());

        assertEquals(Boolean.FALSE, result.get("success"));
        assertEquals("other", result.get("errorReason"));
        assertEquals("FATAL: password authentication failed for user \"user\"", result.get("error"));
    }

    @Test
    void test_survivesAFailureWithNoMessage() {
        // Map.of rejects null values: an unguarded e.getMessage() turns a driver that throws
        // a message-less SQLException into an NPE (and a 500) instead of a structured failure.
        Map<String, Object> result = service((url, user, password) -> {
            throw new SQLException();
        }).test(request());

        assertEquals(Boolean.FALSE, result.get("success"));
        assertEquals("other", result.get("errorReason"));
        assertEquals("SQLException", result.get("error"));
    }

    @Test
    void test_aProbeFailureIsNotAConnectTimeout() throws SQLException {
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery("SELECT 1"))
            .thenThrow(new SQLTimeoutException("canceling statement due to statement timeout"));

        Map<String, Object> result = service((url, user, password) -> connection).test(request());

        assertEquals(Boolean.FALSE, result.get("success"));
        assertEquals("other", result.get("errorReason"),
            "the SELECT 1 probe runs AFTER a successful handshake — the connect-timeout "
                + "classification must not widen to cover it");
        assertEquals("canceling statement due to statement timeout", result.get("error"));
    }

    @Test
    void test_successReturnsNoErrorReasonKey() throws SQLException {
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet resultSet = mock(ResultSet.class);
        DatabaseMetaData metaData = mock(DatabaseMetaData.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery("SELECT 1")).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        when(connection.getMetaData()).thenReturn(metaData);
        when(metaData.getDatabaseProductVersion()).thenReturn("15.2");

        Map<String, Object> result = service((url, user, password) -> connection).test(request());

        assertEquals(Boolean.TRUE, result.get("success"));
        assertEquals("15.2", result.get("serverVersion"));
        assertFalse(result.containsKey("errorReason"),
            "the success shape is unchanged (spec §5.1) — errorReason is ABSENT, not null");
    }

    // ─── openJdbcConnection(String) — spec §5.2 ──────────────────────────

    @Test
    void openJdbcConnection_throwsConnectTimeoutExceptionOnTheDeadline() {
        ConnectionStore store = mock(ConnectionStore.class);
        KeychainStore keychain = mock(KeychainStore.class);
        when(store.findById("conn-1")).thenReturn(Optional.of(config()));
        when(keychain.retrieve("conn-1")).thenReturn("pw");

        ConnectionService service = new ConnectionService(store, keychain,
            DatabaseStrategyFactory.withBuiltinStrategies(), parked(), DEADLINE_MS, DEADLINE_MS);

        SQLException thrown = assertThrows(SQLException.class,
            () -> service.openJdbcConnection("conn-1"));

        assertTrue(thrown instanceof SQLException,
            "the existing catch (SQLException) call sites must be unaffected (spec §5.2)");
        assertFalse(thrown instanceof SQLTimeoutException,
            "the deadline must NOT be a SQLTimeoutException: ExecutionEngine / RowTraceEngine "
                + "classify that type as a query timeout and would report \"Query timed out "
                + "after Ns\" for a handshake that never ran a query (spec §5.2)");
        assertTrue(thrown.getMessage().contains("timed out"), "was: " + thrown.getMessage());
        assertFalse(thrown.getMessage().contains(DRIVER_TEXT));
    }

    @Test
    void openJdbcConnection_handsTheDialectUrlToTheHandshake() throws SQLException {
        ConnectionStore store = mock(ConnectionStore.class);
        KeychainStore keychain = mock(KeychainStore.class);
        when(store.findById("conn-1")).thenReturn(Optional.of(config()));
        when(keychain.retrieve("conn-1")).thenReturn("pw");

        Connection connection = mock(Connection.class);
        List<String> seenUrls = new ArrayList<>();
        ConnectionService service = new ConnectionService(store, keychain,
            DatabaseStrategyFactory.withBuiltinStrategies(),
            (url, user, password) -> {
                seenUrls.add(url);
                return connection;
            }, DEADLINE_MS, DEADLINE_MS);

        assertSame(connection, service.openJdbcConnection("conn-1"));
        assertEquals(List.of("jdbc:postgresql://" + URL_HOST + ":5432/testdb"), seenUrls);
    }
}
