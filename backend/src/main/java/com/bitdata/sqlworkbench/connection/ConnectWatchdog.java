package com.bitdata.sqlworkbench.connection;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Application-owned deadline around a JDBC handshake.
 *
 * <p>{@code DriverManager.getConnection} honours no deadline of its own: an unroutable host
 * is bounded only by the platform TCP connect timeout — measured ≈10s (two runs, 10069ms /
 * 10055ms) against RFC1918 blackhole 10.255.255.1 on the 2026-10-08 probe
 * (`.scratch/e2e-gate-green-2026-10/probe/test-connection-blackhole-probe.mjs`). This
 * watchdog puts the call on a worker thread and gives up when *we* say so, which also makes
 * the timeout classifiable without reading any driver-specific SQLState or message text.
 *
 * <p>Rejected alternatives (spec §4.1): {@code DriverManager.setLoginTimeout} is JVM-global
 * mutable state that concurrent connects would clobber, and is a no-op for some drivers;
 * per-dialect JDBC URL parameters ({@code connectTimeout} / {@code loginTimeout} /
 * {@code socketTimeout}) differ in name and unit per driver and have no anchor in this repo.
 *
 * <p>Known residual (spec §9.1): after the deadline the worker thread stays parked inside the
 * driver until the platform TCP bound elapses — {@code Future.cancel(true)} cannot interrupt a
 * JDBC handshake. That is exactly why the pool is CACHED and not fixed-size-1: a single parked
 * connect must not serialize every later connect behind it. Threads are daemons so a parked
 * handshake can never hold the JVM open.
 */
final class ConnectWatchdog {

    private static final Logger log = LoggerFactory.getLogger(ConnectWatchdog.class);

    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "jdbc-connect-watchdog");
        thread.setDaemon(true);
        return thread;
    });

    private ConnectWatchdog() {
    }

    /**
     * Runs {@code connect} with an application-owned deadline.
     *
     * @return the connection the callable produced, when it finishes in time
     * @throws ConnectTimeoutException when the deadline expires first — the message is ours, so
     *                                 it is locale-independent. Deliberately not a
     *                                 {@link java.sql.SQLTimeoutException}: see that class.
     * @throws SQLException            when the callable fails first (thrown as-is, keeping its
     *                                 driver detail), or when the wait is interrupted
     */
    static Connection callWithDeadline(Callable<Connection> connect, long timeoutMillis) throws SQLException {
        Future<Connection> future = EXECUTOR.submit(connect);
        try {
            return future.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            log.warn("JDBC handshake exceeded the {}ms application deadline", timeoutMillis);
            throw new ConnectTimeoutException("Connection timed out after " + describeDeadline(timeoutMillis));
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof SQLException sqlException) {
                throw sqlException;
            }
            throw new SQLException("JDBC handshake failed: " + cause, cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SQLException("Interrupted while waiting for the JDBC handshake", e);
        }
    }

    /**
     * Whole-second deadlines read as {@code 5s} (the production shapes); sub-second ones are
     * only used by tests and read as {@code 200ms} rather than a misleading {@code 0s}.
     */
    private static String describeDeadline(long timeoutMillis) {
        return timeoutMillis % 1_000L == 0L ? (timeoutMillis / 1_000L) + "s" : timeoutMillis + "ms";
    }
}