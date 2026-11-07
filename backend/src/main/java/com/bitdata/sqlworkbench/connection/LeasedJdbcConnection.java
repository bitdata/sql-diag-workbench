package com.bitdata.sqlworkbench.connection;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;

/** Adds close-confirmation ownership to a physical JDBC connection. */
final class LeasedJdbcConnection implements InvocationHandler {
    private final Connection delegate;
    private final WorkloadLeaseManager.Ticket ticket;
    private final WorkloadLeaseManager manager;
    private volatile boolean confirmedClosed;
    private boolean retryScheduled;

    private LeasedJdbcConnection(Connection delegate, WorkloadLeaseManager.Ticket ticket, WorkloadLeaseManager manager) {
        this.delegate = delegate;
        this.ticket = ticket;
        this.manager = manager;
    }

    static Connection wrap(Connection delegate, WorkloadLeaseManager.Ticket ticket, WorkloadLeaseManager manager) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
            new LeasedJdbcConnection(delegate, ticket, manager));
    }

    @Override
    public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args) throws Throwable {
        String name = method.getName();
        if (method.getDeclaringClass() == Object.class) {
            return switch (name) {
                case "toString" -> "LeasedJdbcConnection(" + delegate + ")";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> method.invoke(delegate, args);
            };
        }
        if (name.equals("close") && method.getParameterCount() == 0) {
            closeOwned();
            return null;
        }
        if (name.equals("isClosed") && method.getParameterCount() == 0) {
            boolean closed = (boolean) invokeDelegate(method, args);
            if (closed) confirmClosed();
            return closed;
        }
        if (name.equals("unwrap") && args != null && args.length == 1 && args[0] == Connection.class) return proxy;
        if (name.equals("isWrapperFor") && args != null && args.length == 1 && args[0] == Connection.class) return true;
        return invokeDelegate(method, args);
    }

    private Object invokeDelegate(java.lang.reflect.Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(delegate, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private synchronized void closeOwned() throws SQLException {
        if (confirmedClosed) return;
        try {
            delegate.close();
            confirmClosed();
        } catch (Exception closeFailure) {
            try {
                if (delegate.isClosed()) {
                    confirmClosed();
                } else {
                    scheduleRetry();
                }
            } catch (Exception stateFailure) {
                closeFailure.addSuppressed(stateFailure);
                scheduleRetry();
            }
            if (closeFailure instanceof SQLException sqlFailure) throw sqlFailure;
            throw (RuntimeException) closeFailure;
        }
    }

    private synchronized void scheduleRetry() {
        if (retryScheduled || confirmedClosed) return;
        retryScheduled = true;
        manager.retryClose(delegate, ticket);
    }

    private void confirmClosed() {
        if (confirmedClosed) return;
        confirmedClosed = true;
        ticket.clearUnconfirmed();
        ticket.release();
    }
}
