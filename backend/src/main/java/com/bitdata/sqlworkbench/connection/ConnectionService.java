package com.bitdata.sqlworkbench.connection;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class ConnectionService {

    private static final Logger log = LoggerFactory.getLogger(ConnectionService.class);
    private static final int TEST_TIMEOUT_SECONDS = 5;

    /**
     * Handshake deadline for the {@code Test Connection} path — the user is watching a
     * "Testing..." button, so the application, not the platform TCP bound, decides when
     * to give up (spec D3).
     */
    static final long TEST_CONNECT_TIMEOUT_MS = 5_000L;

    /**
     * Handshake deadline for workload connections. Longer than the test path because a real
     * login may legitimately be slow, but still the application's bound rather than the
     * ≈10s platform one (spec D3).
     */
    static final long WORKLOAD_CONNECT_TIMEOUT_MS = 30_000L;

    /**
     * Test seam for the raw JDBC handshake. Production is
     * {@link DriverManager#getConnection(String, String, String)}; unit tests inject a
     * connector that parks, so the deadline can be exercised with no driver and no network.
     */
    @FunctionalInterface
    interface JdbcConnector {
        Connection connect(String url, String username, String password) throws SQLException;
    }

    private final ConnectionStore store;
    private final KeychainStore keychain;
    private final DatabaseStrategyFactory strategyFactory;
    private final JdbcConnector connector;
    private final long testConnectTimeoutMillis;
    private final long workloadConnectTimeoutMillis;
    private final WorkloadLeaseManager workloadLeases;

    /**
     * Production constructor. The {@code @Autowired} is load-bearing, not decorative: this
     * class now declares TWO constructors (this one and the test seam below), and Spring —
     * seeing no no-arg constructor and no annotated candidate — fails context startup with
     * "No default constructor found". The whole {@code @SpringBootTest} surface depends on it.
     */
    @Autowired
    public ConnectionService(ConnectionStore store, KeychainStore keychain, DatabaseStrategyFactory strategyFactory,
                             WorkloadLeaseManager workloadLeases) {
        this(store, keychain, strategyFactory, DriverManager::getConnection,
            TEST_CONNECT_TIMEOUT_MS, WORKLOAD_CONNECT_TIMEOUT_MS, workloadLeases);
    }

    /** Test seam — see {@link JdbcConnector}. */
    ConnectionService(ConnectionStore store, KeychainStore keychain, DatabaseStrategyFactory strategyFactory,
                      JdbcConnector connector, long testConnectTimeoutMillis, long workloadConnectTimeoutMillis) {
        this(store, keychain, strategyFactory, connector, testConnectTimeoutMillis, workloadConnectTimeoutMillis,
            new WorkloadLeaseManager());
    }

    ConnectionService(ConnectionStore store, KeychainStore keychain, DatabaseStrategyFactory strategyFactory,
                      JdbcConnector connector, long testConnectTimeoutMillis, long workloadConnectTimeoutMillis,
                      WorkloadLeaseManager workloadLeases) {
        this.store = store;
        this.keychain = keychain;
        this.strategyFactory = strategyFactory;
        this.connector = connector;
        this.testConnectTimeoutMillis = testConnectTimeoutMillis;
        this.workloadConnectTimeoutMillis = workloadConnectTimeoutMillis;
        this.workloadLeases = workloadLeases;
    }

    public List<ConnectionConfig> listAll() {
        keychain.migrate();
        return store.findAll().stream().map(c -> c.withCredentials(keychain.state(c.id()))).toList();
    }

    public Optional<ConnectionConfig> getById(String id) {
        return store.findById(id).map(c -> c.withCredentials(keychain.state(c.id())));
    }

    @org.springframework.transaction.annotation.Transactional
    public ConnectionConfig create(ConnectionRequest request) {
        String dialect = request.dialect() != null ? request.dialect().toLowerCase() : "postgresql";
        String keychainRef = "sqlworkbench/connection/" + UUID.randomUUID().toString();

        keychain.requireAvailable();
        ConnectionConfig created = store.insert(
            request.name(),
            request.host(),
            request.port(),
            request.database(),
            request.username(),
            dialect,
            keychainRef,
            true,
            request.connectionType(),
            Boolean.TRUE.equals(request.trustServerCertificate())
        );
        if (request.password() != null && !request.password().isEmpty() && !Boolean.TRUE.equals(request.noPassword()))
            keychain.store(created.id(),request.password());
        else keychain.noPassword(created.id());
        return created.withCredentials(keychain.state(created.id()));
    }

    @org.springframework.transaction.annotation.Transactional
    public Optional<ConnectionConfig> update(String id, ConnectionRequest request) {
        Optional<ConnectionConfig> existing = store.findById(id);
        if (existing.isEmpty()) {
            log.warn("Update failed: connection not found (id={})", id);
            return Optional.empty();
        }

        keychain.requireAvailable();
        String dialect = request.dialect() != null ? request.dialect().toLowerCase() : existing.get().dialect();

        if (Boolean.TRUE.equals(request.noPassword())) keychain.noPassword(id);
        else if (request.password() != null && !request.password().isEmpty()) {
            keychain.store(id, request.password());
        }

        return store.update(
            id,
            request.name() != null ? request.name() : existing.get().name(),
            request.host() != null ? request.host() : existing.get().host(),
            request.port() > 0 ? request.port() : existing.get().port(),
            request.database() != null ? request.database() : existing.get().database(),
            request.username() != null ? request.username() : existing.get().username(),
            dialect,
            true,
            request.connectionType(),
            request.trustServerCertificate() != null ? Boolean.TRUE.equals(request.trustServerCertificate())
                : existing.get().trustServerCertificate()
        ).map(updated -> {
            log.info("Updated connection: {} ({})", updated.name(), updated.id());
            return updated.withCredentials(keychain.state(updated.id()));
        });
    }

    @org.springframework.transaction.annotation.Transactional
    public boolean delete(String id) {
        Optional<ConnectionConfig> config = store.findById(id);
        if (config.isPresent()) {
            keychain.delete(id);
        }
        return store.delete(id);
    }

    public String getPassword(String connectionId) {
        Optional<ConnectionConfig> config = store.findById(connectionId);
        if (config.isEmpty()) throw new CredentialFailure("CREDENTIAL_MISSING",404,"The connection no longer exists. Reopen connection settings.");
        return keychain.retrieve(connectionId);
    }

    /**
     * Open a fresh JDBC {@link Connection} for the given connection id. The
     * caller owns the connection and is responsible for closing it (try-with-
     * resources). Each call returns a new physical connection — the caller
     * should NOT assume it can be pooled or reused.
     *
     * <p>Used by {@link com.bitdata.sqlworkbench.execution.ExecutionEngine} to
     * run the EXPLAIN path on a connection independent of the SELECT's, so a
     * late {@code Statement.cancel()} from the SELECT's query-timeout timer
     * can't race the EXPLAIN.
     */
    public Connection openJdbcConnection(String connectionId) throws java.sql.SQLException {
        return openJdbcConnection(connectionId, null);
    }

    /** Capture configuration once; validate its identity before reading credentials. */
    public Connection openJdbcConnection(String connectionId, String expectedRevision) throws java.sql.SQLException {
        return openWithTicket(connectionId, expectedRevision, workloadLeases.acquireOrdinary());
    }

    /** Opens the experiment's physical connection under a retained reference of its exclusive ticket. */
    public Connection openJdbcConnection(String connectionId, String expectedRevision,
                                         WorkloadLeaseManager.Ticket exclusiveTicket) throws SQLException {
        if (exclusiveTicket == null || exclusiveTicket.kind() != WorkloadLeaseManager.Kind.EXCLUSIVE) {
            throw new SQLException("EXCLUSIVE_WORKLOAD_TICKET_REQUIRED");
        }
        return openWithTicket(connectionId, expectedRevision, exclusiveTicket.retain());
    }

    public WorkloadLeaseManager.Ticket acquireExclusiveWorkload() throws SQLException {
        return workloadLeases.acquireExclusive();
    }

    private Connection openWithTicket(String connectionId, String expectedRevision,
                                      WorkloadLeaseManager.Ticket connectionTicket) throws SQLException {
        try {
            Optional<ConnectionConfig> config = store.findById(connectionId);
            if (config.isEmpty()) throw new SQLException("CONNECTION_CHANGED");
            ConnectionConfig cfg = config.get();
            if (expectedRevision != null && !ConnectionIdentity.revision(cfg).equals(expectedRevision))
                throw new SQLException("CONNECTION_CHANGED");
            String password = keychain.retrieve(connectionId);
            DatabaseStrategy strategy = strategyFactory.get(cfg.dialect());
            String url;
            if (strategy instanceof SqlServerStrategy sqlServerStrategy) {
                url = sqlServerStrategy.getJdbcUrl(cfg.host(), cfg.port(), cfg.database(), cfg.trustServerCertificate());
            } else {
                url = strategy.getJdbcUrl(cfg.host(), cfg.port(), cfg.database());
            }
            Connection connected = connectWithOwnership(url, cfg.username(), password, workloadConnectTimeoutMillis, connectionTicket);
            return workloadLeases.track(connected, connectionTicket);
        } catch (SQLException | RuntimeException failure) {
            connectionTicket.release();
            throw failure;
        }
    }

    private Connection connectWithOwnership(String url, String username, String password, long timeoutMillis,
                                            WorkloadLeaseManager.Ticket connectionTicket) throws SQLException {
        WorkloadLeaseManager.Ticket handshakeTicket = connectionTicket.retain();
        AtomicBoolean ownershipTransferred = new AtomicBoolean();
        try {
            Connection connected = ConnectWatchdog.callWithDeadline(
                () -> connector.connect(url, username, password), timeoutMillis,
                late -> closeLateConnection(late, handshakeTicket),
                handshakeTicket::release,
                () -> ownershipTransferred.set(true));
            handshakeTicket.release();
            return connected;
        } catch (SQLException | RuntimeException failure) {
            if (!ownershipTransferred.get()) handshakeTicket.release();
            throw failure;
        }
    }

    private void closeLateConnection(Connection late, WorkloadLeaseManager.Ticket ticket) {
        try {
            late.close();
        } catch (SQLException | RuntimeException closeFailure) {
            ticket.markUnconfirmed("CONNECTION_CLEANUP_UNCONFIRMED");
            workloadLeases.retryClose(late, ticket);
            log.warn("Late JDBC connection cleanup is unconfirmed: {}", closeFailure.getMessage(), closeFailure);
            return;
        }
        ticket.release();
    }

    public Map<String, Object> test(ConnectionRequest request) {
        long start = System.currentTimeMillis();
        try { keychain.requireAvailable(); }
        catch (CredentialFailure e) { return Map.of("success",false,"error",e.getMessage(),"errorReason",e.code,"degraded",true); }
        String testPassword=request.password();
        if(request.connectionId()!=null && (testPassword==null||testPassword.isEmpty()) && !Boolean.TRUE.equals(request.noPassword())) {
            try { testPassword=getPassword(request.connectionId()); }
            catch(CredentialFailure e) { return Map.of("success",false,"error",e.getMessage(),"errorReason",e.code,"degraded",true); }
        }
        if(Boolean.TRUE.equals(request.noPassword())) testPassword="";
        final String passwordForTest=testPassword;
        String dialect = request.dialect() != null ? request.dialect().toLowerCase() : "postgresql";
        DatabaseStrategy strategy = strategyFactory.get(dialect);
        String url;
        if (strategy instanceof OracleStrategy oracleStrategy && request.connectionType() != null) {
            url = oracleStrategy.getJdbcUrl(request.host(), request.port(), request.database(), request.connectionType());
        } else if (strategy instanceof SqlServerStrategy sqlServerStrategy) {
            url = sqlServerStrategy.getJdbcUrl(request.host(), request.port(), request.database(),
                Boolean.TRUE.equals(request.trustServerCertificate()));
        } else {
            url = strategy.getJdbcUrl(request.host(), request.port(), request.database());
        }

        // Handshake first, with the application-owned deadline and its own catch: the
        // `connect-timeout` classification describes the handshake ONLY (spec §5.1), and the
        // deadline message is ours rather than the driver's localized text.
        Connection conn;
        WorkloadLeaseManager.Ticket connectionTicket;
        try {
            connectionTicket = workloadLeases.acquireOrdinary();
        } catch (SQLException busy) {
            return Map.of("success", false, "latency", System.currentTimeMillis() - start,
                "error", errorText(busy), "errorReason", busy instanceof WorkloadBusyException ? "WORKLOAD_BUSY" : "CONNECTION_ADMISSION_FAILED", "degraded", true);
        }
        try {
            conn = connectWithOwnership(url, request.username(), passwordForTest, testConnectTimeoutMillis, connectionTicket);
            conn = workloadLeases.track(conn, connectionTicket);
        } catch (ConnectTimeoutException e) {
            connectionTicket.release();
            long latency = System.currentTimeMillis() - start;
            log.warn("Connection test handshake timed out: {} ({}ms)", url, latency);
            return Map.of("success", false, "latency", latency,
                "error", errorText(e), "errorReason", "connect-timeout");
        } catch (Exception e) {
            connectionTicket.release();
            long latency = System.currentTimeMillis() - start;
            log.warn("Connection test failed: {} - {}", url, e.getMessage());
            return Map.of("success", false, "latency", latency,
                "error", errorText(e), "errorReason", "other");
        }

        try (Connection c = conn) {
            if (strategy.supportsReadOnly()) {
                c.setReadOnly(true);
            }
            try (Statement stmt = c.createStatement()) {
                stmt.setQueryTimeout(TEST_TIMEOUT_SECONDS);
                try (ResultSet rs = stmt.executeQuery("SELECT 1")) {
                    rs.next();
                }
            }
            long latency = System.currentTimeMillis() - start;
            String serverVersion = c.getMetaData().getDatabaseProductVersion();
            log.info("Connection test successful: {} ({}ms)", url, latency);
            return Map.of("success", true, "latency", latency, "serverVersion", serverVersion);
        } catch (Exception e) {
            // The handshake succeeded, so nothing here can be a connect timeout — including a
            // SQLTimeoutException from the SELECT 1 probe's own query timeout.
            long latency = System.currentTimeMillis() - start;
            log.warn("Connection test probe failed: {} - {}", url, e.getMessage());
            return Map.of("success", false, "latency", latency,
                "error", errorText(e), "errorReason", "other");
        }
    }

    /**
     * {@code Map.of} rejects null values, and a driver may throw a message-less
     * {@link SQLException}: without this guard the failure branch would NPE (and surface as a
     * 500) instead of returning a structured failure. The class name is a truthful, non-null
     * stand-in — it loses the driver's detail, which was never there.
     */
    private static String errorText(Exception e) {
        String message = e.getMessage();
        return message != null && !message.isEmpty() ? message : e.getClass().getSimpleName();
    }
}
