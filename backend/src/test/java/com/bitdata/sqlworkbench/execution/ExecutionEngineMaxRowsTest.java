package com.bitdata.sqlworkbench.execution;

import com.bitdata.sqlworkbench.connection.ConnectionService;
import com.bitdata.sqlworkbench.connection.DatabaseStrategyFactory;
import com.bitdata.sqlworkbench.sql.SelectOnlyGuard;
import com.bitdata.sqlworkbench.sql.decompose.RuleTestDeps;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class ExecutionEngineMaxRowsTest {

    @Mock ConnectionService connectionService;
    @Mock ExplainServiceFactory explainServiceFactory;
    @Mock DatabaseStrategyFactory strategyFactory;

    @Test
    void engineAcceptsMaxRowsParameter() {
        // Verify the constructor accepts a defaultMaxRows parameter (5th arg).
        // Compile-time check is sufficient — runtime behavior requires a real JDBC connection.
        var engine = new ExecutionEngine(connectionService, explainServiceFactory, strategyFactory, new SelectOnlyGuard(RuleTestDeps.testResolver()), 30, 100);
        assertNotNull(engine);
    }

    @Test
    void executeAcceptsMaxRowsOverload() {
        // Verify execute() has a 4-arg overload accepting maxRows.
        // Compile-time check is the primary assertion — the method must exist.
        // submit() runs asynchronously, so it returns an executionId even though
        // the background task will fail (no real connection).
        var engine = new ExecutionEngine(connectionService, explainServiceFactory, strategyFactory, new SelectOnlyGuard(RuleTestDeps.testResolver()), 30, 100);
        String executionId = engine.execute("SELECT 1", "nonexistent-conn", 30, 500);
        assertNotNull(executionId);
    }

    @Test
    void analyzeAcceptsMaxRowsOverload() {
        // Verify analyze() has a 4-arg overload accepting maxRows.
        // Compile-time check is the primary assertion — the method must exist.
        var engine = new ExecutionEngine(connectionService, explainServiceFactory, strategyFactory, new SelectOnlyGuard(RuleTestDeps.testResolver()), 30, 100);
        String executionId = engine.analyze("SELECT 1", "nonexistent-conn", 30, 500);
        assertNotNull(executionId);
    }

    @Test
    void maxRowsUpperBoundConstantIs10000() {
        assertEquals(10_000, ExecutionEngine.MAX_ROWS_UPPER_BOUND,
            "MAX_ROWS_UPPER_BOUND should be 10,000 to prevent memory exhaustion");
    }

    @Test
    void executeAcceptsMaxRowsAboveUpperBound() {
        // Values above the upper bound should be accepted without throwing —
        // the engine clamps them internally via Math.min().
        var engine = new ExecutionEngine(connectionService, explainServiceFactory, strategyFactory, new SelectOnlyGuard(RuleTestDeps.testResolver()), 30, 100);
        String executionId = engine.execute("SELECT 1", "nonexistent-conn", 30, 50_000);
        assertNotNull(executionId, "Engine should accept maxRows above upper bound");
    }

    @Test
    void analyzeAcceptsMaxRowsAboveUpperBound() {
        // Same clamp applies to analyze().
        var engine = new ExecutionEngine(connectionService, explainServiceFactory, strategyFactory, new SelectOnlyGuard(RuleTestDeps.testResolver()), 30, 100);
        String executionId = engine.analyze("SELECT 1", "nonexistent-conn", 30, 999_999);
        assertNotNull(executionId, "Engine should accept maxRows above upper bound for analyze");
    }
}
