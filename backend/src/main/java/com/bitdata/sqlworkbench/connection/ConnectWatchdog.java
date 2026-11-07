package com.bitdata.sqlworkbench.connection;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.function.Consumer;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Application-owned JDBC handshake deadline with explicit late-connection ownership. */
final class ConnectWatchdog {
    private static final Logger log = LoggerFactory.getLogger(ConnectWatchdog.class);
    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "jdbc-connect-watchdog");
        thread.setDaemon(true);
        return thread;
    });

    private ConnectWatchdog() {}

    static Connection callWithDeadline(Callable<Connection> connect, long timeoutMillis) throws SQLException {
        return callWithDeadline(connect, timeoutMillis, ConnectWatchdog::closeLateConnection);
    }

    /**
     * Runs a handshake under a caller deadline. A driver is allowed to ignore interruption;
     * if it returns a connection after timeout, {@code lateConnection} owns that connection
     * and must close it. The callback runs on the watchdog worker, after the driver call returns.
     */
    static Connection callWithDeadline(Callable<Connection> connect, long timeoutMillis,
                                       Consumer<Connection> lateConnection) throws SQLException {
        return callWithDeadline(connect, timeoutMillis, lateConnection, () -> {});
    }

    static Connection callWithDeadline(Callable<Connection> connect, long timeoutMillis,
                                       Consumer<Connection> lateConnection, Runnable lateFailure) throws SQLException {
        return callWithDeadline(connect, timeoutMillis, lateConnection, lateFailure, () -> {});
    }

    static Connection callWithDeadline(Callable<Connection> connect, long timeoutMillis,
                                       Consumer<Connection> lateConnection, Runnable lateFailure,
                                       Runnable ownershipTransferred) throws SQLException {
        Outcome outcome = new Outcome();
        AtomicReference<Thread> worker = new AtomicReference<>();
        EXECUTOR.execute(() -> run(connect, lateConnection, lateFailure, outcome, worker));
        try {
            if (outcome.finished.await(timeoutMillis, TimeUnit.MILLISECONDS)) return outcome.resultOrThrow();
            synchronized (outcome) {
                if (outcome.done) return outcome.resultOrThrow();
                outcome.timedOut = true;
            }
            ownershipTransferred.run();
            Thread connecting = worker.get();
            if (connecting != null) connecting.interrupt();
            log.warn("JDBC handshake exceeded the {}ms application deadline", timeoutMillis);
            throw new ConnectTimeoutException("Connection timed out after " + describeDeadline(timeoutMillis));
        } catch (InterruptedException interrupted) {
            synchronized (outcome) {
                if (outcome.done) return outcome.resultOrThrow();
                outcome.timedOut = true;
            }
            ownershipTransferred.run();
            Thread connecting = worker.get();
            if (connecting != null) connecting.interrupt();
            Thread.currentThread().interrupt();
            throw new SQLException("Interrupted while waiting for the JDBC handshake", interrupted);
        }
    }

    private static void run(Callable<Connection> connect, Consumer<Connection> lateConnection,
                            Runnable lateFailure, Outcome outcome, AtomicReference<Thread> worker) {
        worker.set(Thread.currentThread());
        Connection connection = null;
        try {
            connection = connect.call();
            if (connection == null) throw new SQLException("JDBC_HANDSHAKE_RETURNED_NO_CONNECTION");
            boolean late;
            synchronized (outcome) {
                late = outcome.timedOut;
                if (!late) outcome.result = connection;
                outcome.done = true;
                outcome.notifyAll();
            }
            if (late && connection != null) {
                try {
                    lateConnection.accept(connection);
                } catch (RuntimeException callbackFailure) {
                    log.warn("Late JDBC connection handoff failed: {}", callbackFailure.getMessage(), callbackFailure);
                }
            }
            outcome.finished.countDown();
        } catch (Throwable failure) {
            boolean late;
            synchronized (outcome) {
                outcome.failure = failure;
                late = outcome.timedOut;
                outcome.done = true;
                outcome.notifyAll();
            }
            if (late) {
                try { lateFailure.run(); }
                catch (RuntimeException callbackFailure) {
                    log.warn("Late JDBC handshake failure handoff failed: {}", callbackFailure.getMessage(), callbackFailure);
                }
            }
            outcome.finished.countDown();
        }
    }

    private static void closeLateConnection(Connection connection) {
        try {
            connection.close();
        } catch (SQLException closeFailure) {
            log.warn("Late JDBC connection could not be closed: {}", closeFailure.getMessage(), closeFailure);
        }
    }

    private static final class Outcome {
        private final CountDownLatch finished = new CountDownLatch(1);
        private boolean timedOut;
        private boolean done;
        private Connection result;
        private Throwable failure;

        private synchronized Connection resultOrThrow() throws SQLException {
            if (failure == null) return result;
            if (failure instanceof SQLException sqlException) throw sqlException;
            throw new SQLException("JDBC handshake failed: " + failure, failure);
        }
    }

    private static String describeDeadline(long timeoutMillis) {
        return timeoutMillis % 1_000L == 0L ? (timeoutMillis / 1_000L) + "s" : timeoutMillis + "ms";
    }
}
