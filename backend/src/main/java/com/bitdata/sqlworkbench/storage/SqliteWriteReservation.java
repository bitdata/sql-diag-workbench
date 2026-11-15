package com.bitdata.sqlworkbench.storage;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Qualify a caller's existing SQLite transaction for writing before taking a read snapshot. */
public final class SqliteWriteReservation {
    private SqliteWriteReservation() {}

    public static void acquire(JdbcTemplate jdbc) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.hasResource(jdbc.getDataSource()))
            throw new IllegalStateException("SQLite write reservation requires a caller transaction");
        // A no-op write acquires SQLite's writer lock without invalidating storage previews.
        if (jdbc.update("UPDATE storage_generation SET revision=revision WHERE id=1") != 1)
            throw new IllegalStateException("SQLite write reservation could not be confirmed");
    }
}
