package com.bitdata.sqlworkbench.diagnostics;

import com.bitdata.sqlworkbench.connection.ConnectionConfig;
import com.bitdata.sqlworkbench.connection.ConnectionService;
import com.bitdata.sqlworkbench.connection.DatabaseStrategy;
import com.bitdata.sqlworkbench.connection.DatabaseStrategyFactory;
import com.bitdata.sqlworkbench.sql.SelectOnlyGuard;
import com.bitdata.sqlworkbench.sql.decompose.RuleTestDeps;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.sql.*;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FindingVerifierTest {

    @Mock ConnectionService connectionService;
    @Mock DatabaseStrategyFactory strategyFactory;
    @Mock DatabaseStrategy strategy;
    @Mock Connection connection;
    @Mock PreparedStatement preparedStatement;
    @Mock ResultSet resultSet;

    private FindingVerifier verifier;
    private static final ConnectionConfig PG_CONFIG = new ConnectionConfig(
        "conn-1", "Test", "localhost", 5432, "testdb", "user", "postgresql", null, false, "", "", "direct", false
    );

    @BeforeEach
    void setUp() {
        verifier = new FindingVerifier(connectionService, strategyFactory,
            new SelectOnlyGuard(RuleTestDeps.testResolver()));
    }

    @Test
    void confirmWhenRowsExist() throws Exception {
        when(connectionService.getById("conn-1")).thenReturn(Optional.of(PG_CONFIG));
        when(connectionService.openJdbcConnection("conn-1")).thenReturn(connection);
        when(strategyFactory.get(anyString())).thenReturn(strategy);
        when(strategy.wrapVerificationSql(anyString())).thenReturn("WRAPPED");
        when(strategy.supportsReadOnly()).thenReturn(false);
        when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);
        when(preparedStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getInt(1)).thenReturn(5);

        FindingVerifier.VerifyResult result = verifier.verify("SELECT * FROM t", "conn-1");

        assertEquals(FindingStatus.CONFIRMED, result.status());
        assertTrue(result.summary().contains("confirmed"));
        verify(preparedStatement).setQueryTimeout(10);
        verify(connection).prepareStatement("WRAPPED");
    }

    @Test
    void refuteWhenNoRows() throws Exception {
        when(connectionService.getById("conn-1")).thenReturn(Optional.of(PG_CONFIG));
        when(connectionService.openJdbcConnection("conn-1")).thenReturn(connection);
        when(strategyFactory.get(anyString())).thenReturn(strategy);
        when(strategy.wrapVerificationSql(anyString())).thenReturn("WRAPPED");
        when(strategy.supportsReadOnly()).thenReturn(false);
        when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);
        when(preparedStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getInt(1)).thenReturn(0);

        FindingVerifier.VerifyResult result = verifier.verify("SELECT 1", "conn-1");

        assertEquals(FindingStatus.REFUTED, result.status());
        assertTrue(result.summary().contains("not reproduced"));
    }

    @Test
    void suspectWhenExecutionFails() throws Exception {
        when(connectionService.getById("conn-1")).thenReturn(Optional.of(PG_CONFIG));
        when(strategyFactory.get(anyString())).thenReturn(strategy);
        when(strategy.wrapVerificationSql(anyString())).thenReturn("WRAPPED");
        when(connectionService.openJdbcConnection("conn-1")).thenThrow(new SQLException("Connection refused"));

        FindingVerifier.VerifyResult result = verifier.verify("SELECT 1", "conn-1");

        assertEquals(FindingStatus.SUSPECTED, result.status());
        assertTrue(result.summary().contains("Error"));
    }

    @Test
    void rejectsNonSelectWithoutOpeningConnection() throws Exception {
        when(connectionService.getById("conn-1")).thenReturn(Optional.of(PG_CONFIG));
        when(strategyFactory.get(anyString())).thenReturn(strategy);

        FindingVerifier.VerifyResult result = verifier.verify("DELETE FROM users", "conn-1");

        assertEquals(FindingStatus.SUSPECTED, result.status());
        assertTrue(result.summary().contains("Only SELECT"), result.summary());
        verify(connectionService, never()).openJdbcConnection(anyString());
    }
}
