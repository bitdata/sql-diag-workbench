package com.bitdata.sqlworkbench.diagnostics.trace;

import com.bitdata.sqlworkbench.connection.ConnectTimeoutException;
import com.bitdata.sqlworkbench.connection.ConnectionConfig;
import com.bitdata.sqlworkbench.connection.ConnectionService;
import com.bitdata.sqlworkbench.connection.DatabaseStrategy;
import com.bitdata.sqlworkbench.connection.DatabaseStrategyFactory;
import com.bitdata.sqlworkbench.diagnostics.RuleRegistry;
import com.bitdata.sqlworkbench.sql.model.FragmentNode;
import com.bitdata.sqlworkbench.sql.model.FragmentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RowTraceEngineTest {

    private ConnectionService connectionService;
    private DatabaseStrategyFactory strategyFactory;
    private DatabaseStrategy strategy;
    private TraceStore traceStore;
    private RuleRegistry ruleRegistry;
    private RowTraceEngine engine;
    private Connection mockConnection;
    private PreparedStatement mockStmt;
    private ResultSet mockRs;

    @BeforeEach
    void setUp() throws Exception {
        connectionService = mock(ConnectionService.class);
        strategyFactory = mock(DatabaseStrategyFactory.class);
        strategy = mock(DatabaseStrategy.class);
        traceStore = new TraceStore();
        ruleRegistry = mock(RuleRegistry.class);

        mockConnection = mock(Connection.class);
        mockStmt = mock(PreparedStatement.class);
        mockRs = mock(ResultSet.class);

        lenient().when(connectionService.openJdbcConnection(anyString())).thenReturn(mockConnection);
        when(connectionService.getById(anyString())).thenReturn(
            Optional.of(new ConnectionConfig("c1", "test", "localhost", 5432, "db",
                "user", "postgresql", "ref", true, "2026-01-01", "2026-01-01", "jdbc", false)));
        when(strategyFactory.get(anyString())).thenReturn(strategy);
        lenient().when(strategy.supportsReadOnly()).thenReturn(true);
        lenient().when(mockConnection.prepareStatement(anyString())).thenReturn(mockStmt);
        lenient().when(strategy.getDialectProfile()).thenReturn(new com.bitdata.sqlworkbench.connection.PostgreSqlStrategy().getDialectProfile());
        PreparedStatement metadataStmt = mock(PreparedStatement.class);
        ResultSet metadataRows = mock(ResultSet.class);
        ResultSetMetaData metadata = mock(ResultSetMetaData.class);
        lenient().when(mockConnection.prepareStatement(argThat(sql -> sql != null && sql.startsWith("SELECT * FROM (")))).thenReturn(metadataStmt);
        lenient().when(metadataStmt.executeQuery()).thenReturn(metadataRows);
        lenient().when(metadataRows.getMetaData()).thenReturn(metadata);
        lenient().when(metadata.getColumnCount()).thenReturn(2);
        lenient().when(metadata.getColumnLabel(1)).thenReturn("id");
        lenient().when(metadata.getColumnLabel(2)).thenReturn("order_id");
        lenient().when(ruleRegistry.analyzeAllTrace(any())).thenReturn(List.of());

        engine = new RowTraceEngine(connectionService, strategyFactory, traceStore, ruleRegistry,
            new com.bitdata.sqlworkbench.sql.SelectOnlyGuard(
                com.bitdata.sqlworkbench.sql.decompose.RuleTestDeps.testResolver()),
            new com.bitdata.sqlworkbench.sql.SqlParseEngine(
                com.bitdata.sqlworkbench.sql.decompose.RuleTestDeps.testResolver()),
            30);
    }

    private FragmentNode leaf(String id, String sql) {
        return new FragmentNode(id, FragmentType.JOIN_SPLIT, "Leaf-" + id, sql,
            true, null, 0, "JoinSplitRule", List.of());
    }

    @org.junit.jupiter.api.AfterEach
    void closeWorkers() { engine.shutdown(); traceStore.shutdown(); }

    @Test
    void cancellationBetweenMetadataAndExistsPreventsTheSecondQuery() throws Exception {
        var root = leaf("root", "SELECT 1 AS id");
        var request = new TraceRequest("c1", buildTreeJson(root), List.of(new KeyValue("id", 1)), 5);
        var id = createTrace(request);
        var metadataStmt = mockConnection.prepareStatement("SELECT * FROM (SELECT 1 AS id) AS __trace_sub WHERE 1=0");
        var metadataRows = metadataStmt.executeQuery();
        clearInvocations(mockStmt);
        doAnswer(invocation -> { traceStore.cancel(id); return null; }).when(metadataRows).close();
        engine.executeTraceSync(id, request);
        assertEquals(TraceStatus.CANCELLED, traceStore.toProgress(id).status());
        assertEquals(List.of(), traceStore.toProgress(id).steps());
        verify(mockStmt, never()).executeQuery();
    }

    private FragmentNode parent(String id, List<FragmentNode> children) {
        return new FragmentNode(id, FragmentType.ORIGINAL, "Root", "SELECT * FROM orders",
            true, null, children.size(), null, children);
    }

    private String buildTreeJson(FragmentNode root) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            return mapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private String createTrace(TraceRequest request) {
        String traceId = traceStore.create(request);
        return traceId;
    }

    @Test
    void executeTrace_singleLeaf_hasRows() throws Exception {
        when(mockStmt.executeQuery()).thenReturn(mockRs);
        when(mockRs.next()).thenReturn(true);
        when(mockRs.getBoolean(1)).thenReturn(true);

        FragmentNode leaf = leaf("leaf1", "SELECT * FROM orders");
        FragmentNode root = parent("root", List.of(leaf));

        String treeJson = buildTreeJson(root);
        TraceRequest request = new TraceRequest("c1", treeJson,
            List.of(new KeyValue("order_id", 12345)), 30);

        String traceId = createTrace(request);
        engine.executeTraceSync(traceId, request);

        TraceProgress progress = traceStore.toProgress(traceId);
        assertNotNull(progress);
        assertEquals(TraceStatus.COMPLETED, progress.status());
        assertFalse(progress.steps().isEmpty());
        // At least one step should be HAS_ROWS
        assertTrue(progress.steps().stream()
            .anyMatch(s -> s.status() == TraceStatus.HAS_ROWS));
        assertNull(progress.eliminationStep());
    }

    @Test
    void executeTrace_singleLeaf_noRows_eliminationAtLeaf() throws Exception {
        when(mockStmt.executeQuery()).thenReturn(mockRs);
        when(mockRs.next()).thenReturn(true);
        when(mockRs.getBoolean(1)).thenReturn(false);

        FragmentNode leaf = leaf("leaf1", "SELECT * FROM orders");
        FragmentNode root = parent("root", List.of(leaf));

        String treeJson = buildTreeJson(root);
        TraceRequest request = new TraceRequest("c1", treeJson,
            List.of(new KeyValue("order_id", 99999)), 30);

        String traceId = createTrace(request);
        engine.executeTraceSync(traceId, request);

        TraceProgress progress = traceStore.toProgress(traceId);
        assertEquals(TraceStatus.COMPLETED, progress.status());
        assertNull(progress.eliminationStep());
        assertInstanceOf(EliminationOutcome.Inconclusive.class, progress.eliminationOutcome());
    }

    @Test
    void executeTrace_nonExecutableNode_skipped() throws Exception {
        FragmentNode nonExec = new FragmentNode("ne1", FragmentType.ORIGINAL, "NonExec", "",
            false, null, 0, null, List.of());
        FragmentNode root = parent("root", List.of(nonExec));

        when(mockStmt.executeQuery()).thenReturn(mockRs);
        when(mockRs.next()).thenReturn(true);
        when(mockRs.getBoolean(1)).thenReturn(true);

        String treeJson = buildTreeJson(root);
        TraceRequest request = new TraceRequest("c1", treeJson,
            List.of(new KeyValue("id", 1)), 30);

        String traceId = createTrace(request);
        engine.executeTraceSync(traceId, request);

        TraceProgress progress = traceStore.toProgress(traceId);
        assertNotNull(progress);
        assertEquals(TraceStatus.COMPLETED, progress.status());
        assertTrue(progress.steps().stream()
            .anyMatch(s -> s.status() == TraceStatus.SKIPPED));
    }

    @Test
    void executeTrace_sqlException_errorStep() throws Exception {
        when(mockStmt.executeQuery()).thenThrow(new SQLException("Table not found"));

        FragmentNode leaf = leaf("leaf1", "SELECT * FROM nonexistent");
        FragmentNode root = parent("root", List.of(leaf));

        String treeJson = buildTreeJson(root);
        TraceRequest request = new TraceRequest("c1", treeJson,
            List.of(new KeyValue("id", 1)), 30);

        String traceId = createTrace(request);
        engine.executeTraceSync(traceId, request);

        TraceProgress progress = traceStore.toProgress(traceId);
        assertNotNull(progress);
        assertEquals(TraceStatus.COMPLETED, progress.status());
        assertTrue(progress.steps().stream()
            .anyMatch(s -> s.status() == TraceStatus.ERROR));
        assertTrue(progress.findings().stream()
            .anyMatch(f -> f.ruleId().equals("row-trace-engine")),
            "ERROR step must produce an explicit finding, not be silently swallowed");
    }

    @Test
    void executeTrace_connectDeadlineIsNotAQueryTimeout() throws Exception {
        // Same-class sweep with ExecutionEngine (rule #1). This engine's
        // catch(SQLTimeoutException) is INSIDE executeStep, by which point `conn` is
        // already open — so unlike ExecutionEngine it could never have claimed the
        // handshake. Reachability checked before writing the guard: the connect deadline
        // reaches the outer catch(Exception) instead. See the assertions below.
        when(connectionService.openJdbcConnection(anyString())).thenThrow(
            new ConnectTimeoutException("Connection timed out after 30s"));

        FragmentNode leaf = leaf("leaf1", "SELECT * FROM orders");
        FragmentNode root = parent("root", List.of(leaf));

        String treeJson = buildTreeJson(root);
        TraceRequest request = new TraceRequest("c1", treeJson,
            List.of(new KeyValue("id", 1)), 30);

        String traceId = createTrace(request);
        engine.executeTraceSync(traceId, request);

        TraceProgress progress = traceStore.toProgress(traceId);
        assertNotNull(progress);
        // openJdbcConnection runs ONCE for the whole trace, OUTSIDE executeStep's per-step
        // try — so a connect deadline never reaches that method's catch(SQLTimeoutException)
        // and cannot become a TIMEOUT step. It fails the trace carrying the watchdog's own
        // message. Pinned here because the false reading to guard against is a
        // "Query timed out after Ns" step, which diagnoses a query that never ran.
        assertEquals(TraceStatus.FAILED, progress.status());
        assertTrue(progress.error().contains("Connection timed out after 30s"),
            "the connect deadline's own message must survive: " + progress.error());
        assertTrue(progress.steps().stream().noneMatch(s -> s.status() == TraceStatus.TIMEOUT),
            "a connect deadline must not be reported as a query timeout step: "
                + progress.steps().stream().map(TraceStep::status).toList());
    }

    @Test
    void executeTrace_readOnlyConfigured() throws Exception {
        when(mockStmt.executeQuery()).thenReturn(mockRs);
        when(mockRs.next()).thenReturn(true);
        when(mockRs.getBoolean(1)).thenReturn(true);

        FragmentNode leaf = leaf("leaf1", "SELECT 1");
        FragmentNode root = parent("root", List.of(leaf));

        String treeJson = buildTreeJson(root);
        TraceRequest request = new TraceRequest("c1", treeJson,
            List.of(new KeyValue("id", 1)), 30);

        String traceId = createTrace(request);
        engine.executeTraceSync(traceId, request);

        verify(strategy).configureReadOnly(mockConnection);
    }

    @Test
    void executeTrace_nonSelectFragment_traceFailsWithoutConnection() throws Exception {
        FragmentNode leaf = leaf("leaf1", "DROP TABLE users");
        FragmentNode root = parent("root", List.of(leaf));

        String treeJson = buildTreeJson(root);
        TraceRequest request = new TraceRequest("c1", treeJson,
            List.of(new KeyValue("id", 1)), 30);

        String traceId = createTrace(request);
        engine.executeTraceSync(traceId, request);

        TraceProgress progress = traceStore.toProgress(traceId);
        assertNotNull(progress);
        assertEquals(TraceStatus.FAILED, progress.status());
        assertTrue(progress.error().contains("Only SELECT"), progress.error());
        verify(connectionService, never()).openJdbcConnection(anyString());
    }

    @Test
    void rejectsNonNestedTree() throws Exception {
        // Flat parentIds-only JSON: root claims a parent and nests no children —
        // the serialization contract the frontend guarantees was not met. Must
        // fail fast with the guard message, before any JDBC connection opens.
        String flatJson = "{\"id\":\"root\",\"type\":\"ORIGINAL\",\"label\":\"Root\","
            + "\"sqlFragment\":\"SELECT * FROM orders\",\"executable\":true,"
            + "\"parentIds\":[\"ghost\"],\"children\":[]}";

        TraceRequest request = new TraceRequest("c1", flatJson,
            List.of(new KeyValue("order_id", 1)), 30);

        String traceId = createTrace(request);
        engine.executeTraceSync(traceId, request);

        TraceProgress progress = traceStore.toProgress(traceId);
        assertEquals(TraceStatus.FAILED, progress.status());
        assertEquals("TRACE_INPUT_INVALID", progress.errorReason());
        verify(connectionService, never()).openJdbcConnection(anyString());
    }

    @Test
    void executeTrace_multiNode_topologicalOrder() throws Exception {
        when(mockStmt.executeQuery()).thenReturn(mockRs);
        when(mockRs.next()).thenReturn(true);
        when(mockRs.getBoolean(1)).thenReturn(true);

        FragmentNode leaf1 = leaf("leaf1", "SELECT * FROM orders");
        FragmentNode leaf2 = leaf("leaf2", "SELECT * FROM items");
        FragmentNode joinNode = parent("join", List.of(leaf1, leaf2));
        FragmentNode root = parent("root", List.of(joinNode));

        String treeJson = buildTreeJson(root);
        TraceRequest request = new TraceRequest("c1", treeJson,
            List.of(new KeyValue("order_id", 1)), 30);

        String traceId = createTrace(request);
        engine.executeTraceSync(traceId, request);

        TraceProgress progress = traceStore.toProgress(traceId);
        assertNotNull(progress);
        assertEquals(TraceStatus.COMPLETED, progress.status());
        assertEquals(4, progress.steps().size());
        List<String> ids = progress.steps().stream().map(TraceStep::fragmentId).toList();
        assertTrue(ids.indexOf("leaf1") < ids.indexOf("join"));
        assertTrue(ids.indexOf("leaf2") < ids.indexOf("join"));
        assertTrue(ids.indexOf("join") < ids.indexOf("root"));
    }
}
