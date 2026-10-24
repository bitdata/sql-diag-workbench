package com.bitdata.sqlworkbench.connection;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * DB-free contract tests for {@link ConnectWatchdog} — the application-owned deadline
 * around a JDBC handshake (spec §4.1). No driver, no network: the "slow handshake" is a
 * parked callable.
 */
class ConnectWatchdogTest {

    /** The driver's localized failure text — must never reach the deadline message. */
    private static final String DRIVER_TEXT = "尝试连线已失败。";

    /**
     * Parks like a real driver waiting on a TCP SYN that is never answered, then throws the
     * way a driver does once the platform bound finally elapses.
     *
     * <p>The {@link InterruptedException} is deliberately SWALLOWED (not re-flagged): the
     * deadline's {@code Future.cancel(true)} interrupts this worker, and {@code await()}
     * already clears the flag when it throws. Re-setting it would leave the pooled worker
     * with a stale interrupt that would cancel an unrelated later task.
     */
    private static Connection parkedHandshake() throws SQLException {
        try {
            new CountDownLatch(1).await();
        } catch (InterruptedException e) {
            // The deadline fired and cancelled us — fall through to the driver-style throw.
        }
        throw new SQLException(DRIVER_TEXT);
    }

    @Test
    void expiresOnTheApplicationDeadline() {
        ConnectTimeoutException thrown = assertThrows(ConnectTimeoutException.class,
            () -> ConnectWatchdog.callWithDeadline(ConnectWatchdogTest::parkedHandshake, 200L));

        assertTrue(thrown.getMessage().contains("timed out"),
            "message must carry the timeout wording, was: " + thrown.getMessage());
        assertTrue(thrown.getMessage().contains("200ms"),
            "message must carry the deadline value, was: " + thrown.getMessage());
        assertFalse(thrown.getMessage().contains(DRIVER_TEXT),
            "the deadline message is application-owned — it must never carry the driver's text");
    }

    @Test
    void returnsTheConnectionWhenTheHandshakeFinishesInTime() throws SQLException {
        Connection connection = mock(Connection.class);

        assertSame(connection, ConnectWatchdog.callWithDeadline(() -> connection, 1_000L));
    }

    @Test
    void propagatesTheHandshakeFailureUnchanged() {
        SQLException thrown = assertThrows(SQLException.class,
            () -> ConnectWatchdog.callWithDeadline(() -> {
                throw new SQLException("Connection refused");
            }, 1_000L));

        assertFalse(thrown instanceof ConnectTimeoutException,
            "a fast failure is not a deadline expiry");
        assertEquals("Connection refused", thrown.getMessage());
    }

    @Test
    void driverThrownSqlTimeoutExceptionPropagatesUnchanged() {
        // A driver may throw SQLTimeoutException from the handshake itself (its own login
        // timeout). That is the driver's business, not ours: the watchdog converts only
        // its OWN deadline, so the type's meaning stays unambiguous for callers.
        SQLTimeoutException fromDriver = new SQLTimeoutException("driver login timeout");

        SQLException thrown = assertThrows(SQLTimeoutException.class,
            () -> ConnectWatchdog.callWithDeadline(() -> {
                throw fromDriver;
            }, 1_000L));

        assertSame(fromDriver, thrown, "the driver's own exception must pass through as-is");
        assertFalse(thrown instanceof ConnectTimeoutException);
    }

    @Test
    void deadlineIsAnSqlExceptionButNotASqlTimeoutException() {
        // SQLException, so every existing `catch (SQLException)` still holds the deadline.
        // NOT SQLTimeoutException, because the execution and trace engines classify that
        // type as a query timeout: they would report "Query timed out after Ns" for a
        // handshake that never ran a query — the laundering this type exists to prevent.
        SQLException thrown = assertThrows(SQLException.class,
            () -> ConnectWatchdog.callWithDeadline(ConnectWatchdogTest::parkedHandshake, 100L));

        assertTrue(thrown instanceof ConnectTimeoutException);
        assertFalse(thrown instanceof SQLTimeoutException,
            "the connect deadline must not be a SQLTimeoutException — the engines "
                + "misreport that type as a query timeout");
    }
}