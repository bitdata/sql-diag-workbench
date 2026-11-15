package com.bitdata.sqlworkbench.diagnostics.source;
import com.bitdata.sqlworkbench.connection.*;
import com.bitdata.sqlworkbench.diagnostics.trace.*;
import com.bitdata.sqlworkbench.sql.*;
import org.junit.jupiter.api.Test;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
class SqlServerSourcePlanServiceTest {
    @Test void catalogDenialInjectionNeverBecomesCertifiedSourceOrZero()throws Exception {
        var connections=mock(ConnectionService.class);var connection=mock(Connection.class);var statement=mock(PreparedStatement.class);
        when(connections.getById("c")).thenReturn(Optional.of(new ConnectionConfig("c","mock","mock",1,"mock","mock","sqlserver","mock",true,"0","0","sqlserver",true)));
        when(connections.openJdbcConnectionWithTimeout(eq("c"),anyString(),anyLong())).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);when(statement.executeQuery()).thenThrow(new SQLException("injected catalog denial","42501"));
        var resolver=new SqlDialectResolver(DatabaseStrategyFactory.withBuiltinStrategies());
        var service=new SqlServerSourcePlanService(connections,resolver,new SelectOnlyGuard(resolver));
        var result=service.inspect(new PhysicalSourcePlanService.Request("c","SELECT SalesOrderID AS k FROM Sales.SalesOrderHeader",List.of(new KeyValue("k","43659",KeyValue.Type.DECIMAL))),false);
        assertEquals("UNKNOWN",result.status());assertFalse(result.verified());assertTrue(result.sources().isEmpty());
        assertTrue(result.issues().stream().anyMatch(i->i.errorReason().equals("SOURCE_METADATA_FAILED")));verify(connection).close();
    }
}
