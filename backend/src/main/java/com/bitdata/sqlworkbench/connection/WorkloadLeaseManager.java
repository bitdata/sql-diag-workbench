package com.bitdata.sqlworkbench.connection;

import org.springframework.stereotype.Service;
import jakarta.annotation.PreDestroy;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Coordinates application JDBC work so a controlled experiment has an exclusive window. */
@Service
public final class WorkloadLeaseManager {
    public enum Kind { ORDINARY, EXCLUSIVE }

    public record Snapshot(int ordinaryTickets, boolean exclusiveActive, int unconfirmedTickets) {}

    private final Set<Entry> ordinary = Collections.newSetFromMap(new IdentityHashMap<>());
    private final ScheduledExecutorService cleanup = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "jdbc-lease-cleanup");
        thread.setDaemon(true);
        return thread;
    });
    private Entry exclusive;

    public synchronized Ticket acquireOrdinary() throws SQLException {
        if (exclusive != null) throw new WorkloadBusyException("An exclusive performance experiment is active");
        Entry entry = new Entry(Kind.ORDINARY);
        ordinary.add(entry);
        return new Ticket(this, entry);
    }

    public synchronized Ticket acquireExclusive() throws SQLException {
        if (exclusive != null || !ordinary.isEmpty()) {
            throw new WorkloadBusyException("Database workload is active");
        }
        exclusive = new Entry(Kind.EXCLUSIVE);
        return new Ticket(this, exclusive);
    }

    public synchronized Snapshot snapshot() {
        int unconfirmed = 0;
        for (Entry entry : ordinary) synchronized (entry) { if (entry.unconfirmed) unconfirmed++; }
        if (exclusive != null) synchronized (exclusive) { if (exclusive.unconfirmed) unconfirmed++; }
        return new Snapshot(ordinary.size(), exclusive != null, unconfirmed);
    }

    private synchronized void released(Entry entry) {
        if (entry.kind == Kind.EXCLUSIVE) {
            if (exclusive == entry) exclusive = null;
        } else {
            ordinary.remove(entry);
        }
        notifyAll();
    }

    public Connection track(Connection connection, Ticket ticket) {
        return LeasedJdbcConnection.wrap(connection, ticket, this);
    }

    void retryClose(Connection connection, Ticket ticket) {
        ticket.markUnconfirmed("CONNECTION_CLEANUP_UNCONFIRMED");
        AtomicReference<ScheduledFuture<?>> scheduled = new AtomicReference<>();
        Runnable finish = () -> {
            ScheduledFuture<?> task = scheduled.get();
            if (task != null) task.cancel(false);
        };
        ScheduledFuture<?> retry;
        try {
            retry = cleanup.scheduleWithFixedDelay(() -> {
                if (ticket.isReleased()) { finish.run(); return; }
                try {
                    connection.close();
                    ticket.clearUnconfirmed();
                    ticket.release();
                    finish.run();
                } catch (Exception closeFailure) {
                    try {
                        if (connection.isClosed()) {
                            ticket.clearUnconfirmed();
                            ticket.release();
                            finish.run();
                        } else {
                            ticket.markUnconfirmed("CONNECTION_CLEANUP_UNCONFIRMED");
                        }
                    } catch (Exception stateFailure) {
                        ticket.markUnconfirmed("CONNECTION_CLEANUP_UNCONFIRMED");
                    }
                }
            }, 1, 1, TimeUnit.SECONDS);
        } catch (java.util.concurrent.RejectedExecutionException unavailable) {
            ticket.markUnconfirmed("CONNECTION_CLEANUP_UNCONFIRMED");
            return;
        }
        scheduled.set(retry);
    }

    @PreDestroy
    public void close() {
        cleanup.shutdownNow();
    }

    public static final class Ticket {
        private final WorkloadLeaseManager owner;
        private final Entry entry;
        private volatile boolean released;

        private Ticket(WorkloadLeaseManager owner, Entry entry) {
            this.owner = owner;
            this.entry = entry;
        }

        public Kind kind() { return entry.kind; }

        public boolean isUnconfirmed() { synchronized (entry) { return entry.unconfirmed; } }

        public String errorReason() { synchronized (entry) { return entry.errorReason; } }

        public Ticket retain() {
            synchronized (entry) {
                if (released || entry.released || entry.references <= 0) throw new IllegalStateException("WORKLOAD_TICKET_RELEASED");
                entry.references++;
                return new Ticket(owner, entry);
            }
        }

        /** Releases this reference exactly once. Other owners may still pin the workload. */
        public void release() {
            boolean notifyOwner = false;
            synchronized (entry) {
                if (released) return;
                released = true;
                if (entry.references > 0) entry.references--;
                if (entry.references == 0 && !entry.unconfirmed) {
                    entry.released = true;
                    notifyOwner = true;
                }
            }
            if (notifyOwner) owner.released(entry);
        }

        public void markUnconfirmed(String reason) {
            synchronized (entry) {
                entry.unconfirmed = true;
                entry.errorReason = reason == null || reason.isBlank() ? "CONNECTION_CLEANUP_UNCONFIRMED" : reason;
            }
        }

        public void clearUnconfirmed() {
            boolean notifyOwner = false;
            synchronized (entry) {
                entry.unconfirmed = false;
                entry.errorReason = null;
                if (entry.references == 0 && !entry.released) {
                    entry.released = true;
                    notifyOwner = true;
                }
            }
            if (notifyOwner) owner.released(entry);
        }

        public synchronized boolean isReleased() { return released; }
    }

    private static final class Entry {
        final Kind kind;
        int references = 1;
        boolean released;
        boolean unconfirmed;
        String errorReason;

        Entry(Kind kind) { this.kind = kind; }
    }
}
