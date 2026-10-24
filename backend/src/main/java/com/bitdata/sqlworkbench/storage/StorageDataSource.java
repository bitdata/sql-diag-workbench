package com.bitdata.sqlworkbench.storage;

import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.lang.reflect.*;
import java.sql.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/** Every local JDBC connection holds a shared lease until close, including transactions.
 * VACUUM uses a separate raw connection under the exclusive lease. Remote JDBC is unaffected. */
public class StorageDataSource extends DriverManagerDataSource {
    private final ReentrantReadWriteLock gate = new ReentrantReadWriteLock(true);
    public static class Maintenance extends SQLException {
        public Maintenance() { super("STORAGE_MAINTENANCE: Storage maintenance is in progress. Retry saving.", "SQLITE_BUSY", 5); }
    }
    @Override public Connection getConnection() throws SQLException { return leased(super::getConnection); }
    @Override public Connection getConnection(String username, String password) throws SQLException {
        return leased(() -> super.getConnection(username, password));
    }
    private interface Open { Connection get() throws SQLException; }
    private Connection leased(Open open) throws SQLException {
        if (!gate.readLock().tryLock()) throw new Maintenance();
        Connection raw;
        try {
            raw = open.get();
            try (var s = raw.createStatement()) { s.execute("PRAGMA busy_timeout=5000"); }
            catch(SQLException error) { raw.close(); throw error; }
        } catch (SQLException | RuntimeException error) { gate.readLock().unlock(); throw error; }
        AtomicBoolean closed = new AtomicBoolean();
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
            if (method.getName().equals("close")) {
                if (closed.compareAndSet(false, true)) try { raw.close(); } finally { gate.readLock().unlock(); }
                return null;
            }
            try { return method.invoke(raw, args); } catch (InvocationTargetException error) { throw error.getCause(); }
        });
    }
    public void compact() throws Exception {
        if (!gate.writeLock().tryLock(100, TimeUnit.MILLISECONDS)) throw new Maintenance();
        try (var c = DriverManager.getConnection(getUrl()); var s = c.createStatement()) {
            s.execute("PRAGMA busy_timeout=1000");
            try (var result = s.executeQuery("PRAGMA wal_checkpoint(TRUNCATE)")) {
                if (!result.next() || result.getInt(1) != 0) throw new Maintenance();
            }
            s.execute("VACUUM");
            try (var result = s.executeQuery("PRAGMA wal_checkpoint(TRUNCATE)")) {
                if (!result.next() || result.getInt(1) != 0) throw new Maintenance();
            }
        } finally { gate.writeLock().unlock(); }
    }
}
