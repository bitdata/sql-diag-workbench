package com.bitdata.sqlworkbench.connection;

import java.util.regex.Pattern;

/**
 * S6 — whitelist validation for the components that build JDBC URLs,
 * enforced at the entry of every strategy's getJdbcUrl. Connection-config
 * values are untrusted input for URL assembly: a host like "evil;user=admin"
 * would otherwise become an injected JDBC URL property.
 */
public final class JdbcUrlInputs {

    private static final Pattern HOST = Pattern.compile("^[A-Za-z0-9.\\-_]+$");
    private static final Pattern DATABASE = Pattern.compile("^[A-Za-z0-9_.\\-]+$");

    private JdbcUrlInputs() {
    }

    /**
     * @throws IllegalArgumentException if any component falls outside the
     *         whitelist (host: letters/digits/'.'/-'/_'; port: 1-65535;
     *         database: letters/digits/'_'/'.'/'-').
     */
    public static void validate(String host, int port, String database) {
        if (host == null || host.isEmpty() || !HOST.matcher(host).matches()) {
            throw new IllegalArgumentException(
                "Invalid connection host: only letters, digits, '.', '-' and '_' are allowed");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException(
                "Invalid connection port " + port + ": must be between 1 and 65535");
        }
        if (database == null || database.isEmpty() || !DATABASE.matcher(database).matches()) {
            throw new IllegalArgumentException(
                "Invalid connection database: only letters, digits, '_', '.' and '-' are allowed");
        }
    }
}
