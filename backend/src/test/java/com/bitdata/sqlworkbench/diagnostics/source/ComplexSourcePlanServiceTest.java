package com.bitdata.sqlworkbench.diagnostics.source;

import com.bitdata.sqlworkbench.connection.*;
import com.bitdata.sqlworkbench.diagnostics.trace.KeyValue;
import com.bitdata.sqlworkbench.sql.*;
import org.apache.calcite.sql.parser.SqlParser;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ComplexSourcePlanServiceTest {
    private final SqlDialectResolver resolver=new SqlDialectResolver(DatabaseStrategyFactory.withBuiltinStrategies());
    @Test void mutationAndInvalidKeysAreRejectedBeforeOpeningTheDatabase() throws Exception {
        var connections=mock(ConnectionService.class);var config=mock(ConnectionConfig.class);
        when(config.dialect()).thenReturn("mysql");when(connections.getById("c1")).thenReturn(Optional.of(config));
        var service=new ComplexSourcePlanService(connections,DatabaseStrategyFactory.withBuiltinStrategies(),resolver,new SelectOnlyGuard(resolver));
        for(String sql:List.of("DELETE FROM items","SELECT parent FROM items; DELETE FROM items")) {
            var p=service.inspect(new PhysicalSourcePlanService.Request("c1",sql,List.of(new KeyValue("parent","x",KeyValue.Type.TEXT))));
            assertEquals("UNKNOWN",p.status());assertFalse(p.verified());assertEquals("SOURCE_SQL_UNSUPPORTED",p.errorReason());assertTrue(p.sources().isEmpty());
        }
        assertEquals("SOURCE_INPUT_INVALID",service.inspect(new PhysicalSourcePlanService.Request("c1","SELECT 1",List.of())).errorReason());
        verify(connections,never()).openJdbcConnection(anyString());
    }
    @Test void zeroRowMetadataPreservesWithAndSetBodiesWithoutIntroducingADerivedWrapper() throws Exception {
        for(String dialect:List.of("mysql","postgresql"))for(String sql:List.of(
            "WITH a AS (SELECT parent FROM items WHERE parent='hidden') SELECT parent FROM a ORDER BY parent LIMIT 2 OFFSET 3",
            "SELECT parent FROM items UNION ALL SELECT parent FROM payments ORDER BY parent LIMIT 2 OFFSET 3")) {
            var root=SqlParser.create(sql,resolver.parserConfig(dialect)).parseQuery();
            String zero=ComplexSourcePlanService.zero(root).toSqlString(resolver.getDialect(dialect)).getSql();
            assertTrue(zero.contains("WITH")||zero.contains("UNION ALL"));assertFalse(zero.contains("OFFSET 3"));
            assertTrue(zero.matches("(?s).*(LIMIT 0|FETCH NEXT 0 ROWS ONLY).*"),zero);
            assertFalse(zero.contains("FROM ("),zero);
        }
    }
}
