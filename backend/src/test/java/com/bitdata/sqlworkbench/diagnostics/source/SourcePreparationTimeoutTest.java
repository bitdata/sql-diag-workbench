package com.bitdata.sqlworkbench.diagnostics.source;

import com.bitdata.sqlworkbench.connection.*;
import com.bitdata.sqlworkbench.diagnostics.trace.KeyValue;
import com.bitdata.sqlworkbench.sql.*;
import org.junit.jupiter.api.Test;
import java.sql.*;
import java.util.*;
import java.util.concurrent.Executor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SourcePreparationTimeoutTest {
    @Test void sourcePreparationsInstallNetworkTimeoutBeforeReadOnlyAndRejectTimeoutFailure() throws Exception {
        for(boolean complex:List.of(false,true))for(String dialect:List.of("postgresql","mysql"))for(boolean installFails:List.of(false,true)) {
            var connections=mock(ConnectionService.class);var config=mock(ConnectionConfig.class);
            var connection=mock(Connection.class);var strategy=mock(DatabaseStrategy.class);
            when(config.dialect()).thenReturn(dialect);when(connections.getById("c")).thenReturn(Optional.of(config));
            when(connections.openJdbcConnection("c")).thenReturn(connection);when(strategy.dialect()).thenReturn(dialect);
            if(installFails)doThrow(new SQLException("timeout unsupported")).when(connection).setNetworkTimeout(any(),anyInt());
            else doThrow(new SQLTimeoutException("setup timed out")).when(strategy).configureReadOnly(connection);
            var factory=new DatabaseStrategyFactory(Map.of("test",strategy));
            var resolver=new SqlDialectResolver(DatabaseStrategyFactory.withBuiltinStrategies());var guard=new SelectOnlyGuard(resolver);
            var request=new PhysicalSourcePlanService.Request("c","SELECT n FROM ints",List.of(new KeyValue("n",1)));
            String expected=installFails?"SOURCE_METADATA_FAILED":"SOURCE_METADATA_TIMEOUT";
            if(complex) {var plan=new ComplexSourcePlanService(connections,factory,resolver,guard).inspect(request);
                assertEquals("UNKNOWN",plan.status());assertFalse(plan.verified());assertEquals(expected,plan.errorReason());assertTrue(plan.sources().isEmpty());}
            else {var plan=new PhysicalSourcePlanService(connections,factory,resolver,guard).inspect(request);
                assertEquals("UNKNOWN",plan.status());assertFalse(plan.verified());assertEquals(expected,plan.errorReason());assertNull(plan.sourceSql());}
            var order=inOrder(connection,strategy);
            order.verify(connection).setNetworkTimeout(any(Executor.class),intThat(ms->ms>0&&ms<=5000));
            if(installFails)verify(strategy,never()).configureReadOnly(any());
            else order.verify(strategy).configureReadOnly(connection);
            order.verify(connection).close();
        }
    }
}
