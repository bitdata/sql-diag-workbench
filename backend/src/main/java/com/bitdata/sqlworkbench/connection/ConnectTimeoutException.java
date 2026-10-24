package com.bitdata.sqlworkbench.connection;

import java.sql.SQLException;

/**
 * The application-owned JDBC handshake deadline expired (see {@link ConnectWatchdog}).
 *
 * <p>Deliberately NOT a {@link java.sql.SQLTimeoutException}. {@code SQLTimeoutException} is
 * the JDBC type for a <em>statement</em> exceeding its {@code setQueryTimeout}; the execution
 * and trace engines classify that type as {@code TIMED_OUT} and report
 * {@code "Query timed out after Ns"}. A handshake that never completed ran no query, so
 * inheriting that type would make those engines diagnose a query that never existed — the
 * deadline would be laundered into a fabricated query timeout.
 *
 * <p>Extending {@link SQLException} directly means it falls to the engines' generic
 * {@code catch (SQLException)}, where {@link com.bitdata.sqlworkbench.execution.QueryTimeoutClassifier}
 * does not match it (the message carries no dialect cancel signal) and the caller reports a
 * plain failure carrying {@link #getMessage()} — which the watchdog writes, so it is
 * locale-independent: {@code "Connection timed out after 30s"}.
 */
public class ConnectTimeoutException extends SQLException {

    private static final long serialVersionUID = 1L;

    public ConnectTimeoutException(String message) {
        super(message);
    }
}