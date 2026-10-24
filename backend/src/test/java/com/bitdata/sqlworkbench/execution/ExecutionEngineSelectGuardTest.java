package com.bitdata.sqlworkbench.execution;

import com.bitdata.sqlworkbench.connection.ConnectionConfig;
import com.bitdata.sqlworkbench.connection.ConnectionService;
import com.bitdata.sqlworkbench.connection.DatabaseStrategyFactory;
import com.bitdata.sqlworkbench.execution.model.ExecutionResult;
import com.bitdata.sqlworkbench.execution.model.ExecutionStatus;
import com.bitdata.sqlworkbench.sql.SelectOnlyGuard;
import com.bitdata.sqlworkbench.sql.decompose.RuleTestDeps;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ExecutionEngineSelectGuardTest {

    @Mock ConnectionService connectionService;
    @Mock ExplainServiceFactory explainServiceFactory;
    @Mock DatabaseStrategyFactory strategyFactory;

    private ExecutionEngine engine;

    private static final ConnectionConfig PG_CONFIG = new ConnectionConfig(
        "conn-1", "Test", "localhost", 5432, "testdb", "user", "postgresql", null, false, "", "", "direct", false
    );

    @BeforeEach
    void setUp() {
        engine = new ExecutionEngine(connectionService, explainServiceFactory, strategyFactory,
            new SelectOnlyGuard(RuleTestDeps.testResolver()), 30, 100);
    }

    private ExecutionResult awaitTerminal(String executionId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            ExecutionResult r = engine.getStatus(executionId);
            if (r != null && r.status() != ExecutionStatus.QUEUED && r.status() != ExecutionStatus.RUNNING) {
                return r;
            }
            Thread.sleep(20);
        }
        fail("Execution " + executionId + " did not reach a terminal state within 5s");
        return null;
    }

    @Test
    void executeRejectsNonSelectWithoutOpeningConnection() throws Exception {
        when(connectionService.getById("conn-1")).thenReturn(Optional.of(PG_CONFIG));

        String executionId = engine.execute("DROP TABLE users", "conn-1", 30, 100);
        ExecutionResult result = awaitTerminal(executionId);

        assertEquals(ExecutionStatus.FAILED, result.status());
        assertTrue(result.error().contains("Only SELECT"), result.error());
        verify(connectionService, never()).openJdbcConnection(anyString());
    }

    @Test
    void analyzeValidatesUserSqlBeforeWrapping() throws Exception {
        when(connectionService.getById("conn-1")).thenReturn(Optional.of(PG_CONFIG));

        String executionId = engine.analyze("DELETE FROM users", "conn-1", 30, 100);
        ExecutionResult result = awaitTerminal(executionId);

        assertEquals(ExecutionStatus.FAILED, result.status());
        assertTrue(result.error().contains("Only SELECT"), result.error());
        verify(connectionService, never()).openJdbcConnection(anyString());
    }

    @Test
    void rejectsMultiStatementWithoutOpeningConnection() throws Exception {
        when(connectionService.getById("conn-1")).thenReturn(Optional.of(PG_CONFIG));

        String executionId = engine.execute("SELECT 1; DROP TABLE users", "conn-1", 30, 100);
        ExecutionResult result = awaitTerminal(executionId);

        assertEquals(ExecutionStatus.FAILED, result.status());
        assertTrue(result.error().contains("single statement"), result.error());
        verify(connectionService, never()).openJdbcConnection(anyString());
    }

    @Test
    void validSelectPassesGuardAndReachesConnection() throws Exception {
        when(connectionService.getById("conn-1")).thenReturn(Optional.of(PG_CONFIG));
        when(connectionService.openJdbcConnection("conn-1"))
            .thenThrow(new SQLException("db down"));

        String executionId = engine.execute("SELECT 1", "conn-1", 30, 100);
        ExecutionResult result = awaitTerminal(executionId);

        assertEquals(ExecutionStatus.FAILED, result.status());
        assertTrue(result.error().contains("db down"),
            "Valid SELECT must pass the guard and fail at the connection layer: " + result.error());
    }

    @Test
    void cancelUnknownExecutionIdReturnsFalse() {
        assertFalse(engine.cancel("no-such-execution-id"));
    }

    @Test
    void cancelLiveOrFinishedExecutionNeverThrowsAndLeavesALegalStatus() throws Exception {
        when(connectionService.getById("conn-1")).thenReturn(Optional.of(PG_CONFIG));

        String live = engine.execute("DROP TABLE users", "conn-1", 30, 100);
        // cancel()'s boolean reports whether THIS call won the terminal latch
        // (T5), and the worker's guard rejection can publish FAILED first — the two
        // race, so asserting `true` here is not stable (the latch semantics are
        // pinned in ExecutionEngineTerminalLatchTest). What this test pins is that
        // cancelling a live execution never throws; the terminal read follows below.
        assertDoesNotThrow(() -> engine.cancel(live));
        // Both racers publish a reason-carrying terminal now — the cancel() entry
        // site (T5) and the worker's guard rejection — so the snapshot read must be
        // one of those two, never a half-written status/result pair.
        ExecutionResult afterCancel = awaitTerminal(live);
        assertTrue(afterCancel.status() == ExecutionStatus.CANCELLED
                || afterCancel.status() == ExecutionStatus.FAILED,
            "unexpected terminal after cancel: " + afterCancel.status());

        String finished = engine.execute("DROP TABLE users", "conn-1", 30, 100);
        awaitTerminal(finished);
        engine.cancel(finished);   // must not throw on an already-terminal execution
    }

    @Test
    void everyIdReachesAPublishedTerminalUnderMixedLoad() throws Exception {
        when(connectionService.getById("conn-1")).thenReturn(Optional.of(PG_CONFIG));

        // Pool 4 + queue 10 = 14 accepted; the rest hit AbortPolicy and are
        // published synchronously as FAILED by submit's catch. 30 ids stays under
        // run()'s >50 GC threshold, so no entry can be evicted mid-test.
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            ids.add(engine.execute("DROP TABLE users", "conn-1", 30, 100));
        }

        for (String id : ids) {
            ExecutionResult r = awaitTerminal(id);
            // Two paths can produce a terminal here — guard rejection and pool
            // exhaustion — and both publish a result, so error() must be present.
            // The CANCELLED paths are no longer an exception to this: since T5/T6
            // the entry site and the three worker checkpoints all publish a
            // reason-tagged result.
            assertNotNull(r.error(), "terminal " + r.status() + " without error for " + id);
        }
    }
}
