package com.bitdata.sqlworkbench.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.SQLException;
import java.util.function.Supplier;

/**
 * Retry helper for SQLite operations that may fail with SQLITE_BUSY.
 * With WAL mode and busy_timeout=5000, busy errors are rare but should
 * still be retried rather than silently dropped.
 */
public final class SqliteRetry {

    private static final Logger log = LoggerFactory.getLogger(SqliteRetry.class);
    private static final int MAX_RETRIES = 3;
    private static final long INITIAL_BACKOFF_MS = 50;

    private SqliteRetry() {}

    public static <T> T execute(Supplier<T> operation, String description) {
        long backoff = INITIAL_BACKOFF_MS;

        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                return operation.get();
            } catch (DataAccessException e) {
                Throwable cause = e.getCause();
                if (cause instanceof SQLException sqle
                    && isBusyError(sqle)
                    && attempt < MAX_RETRIES) {
                    log.warn("SQLite busy for '{}', retry {}/{} after {}ms",
                        description, attempt + 1, MAX_RETRIES, backoff);
                    try {
                        Thread.sleep(backoff);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw e;
                    }
                    backoff *= 2;
                } else {
                    log.error("SQLite operation '{}' failed: {}", description, e.getMessage());
                    throw e;
                }
            } catch (RuntimeException e) {
                log.error("SQLite operation '{}' failed: {}", description, e.getMessage());
                throw e;
            }
        }

        throw new RuntimeException("SQLite retry exhausted for: " + description);
    }

    private static boolean isBusyError(SQLException e) {
        String msg = e.getMessage();
        return msg != null && (msg.contains("SQLITE_BUSY")
            || msg.contains("database is locked")
            || msg.contains("database table is locked"));
    }
}
