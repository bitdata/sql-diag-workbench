package com.bitdata.sqlworkbench.diagnostics.trace;
import org.junit.jupiter.api.Test;
import java.sql.PreparedStatement;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class SqlServerObservationSqlTest {
    @Test void sqlServerExistenceIncludesQueueTimeInTotalBudgetWhilePgKeepsItsContract() {
        var clock=new java.util.concurrent.atomic.AtomicLong();var store=new TraceStore(clock::get);
        try {
            var request=new TraceRequest("c","{}",List.of(new KeyValue("k","1",KeyValue.Type.DECIMAL)),30);
            String sqlServer=store.create(request,"sqlserver"),pg=store.create(request,"postgresql");
            clock.set(java.util.concurrent.TimeUnit.SECONDS.toNanos(121));store.remainingTimeout(sqlServer,30);
            assertEquals("TRACE_BUDGET_EXCEEDED",store.toProgress(sqlServer).errorReason());assertFalse(store.canExecute(sqlServer));
            assertEquals(30,store.remainingTimeout(pg,30));assertTrue(store.canExecute(pg));
        } finally {store.shutdown();}
    }
    @Test void cteRemainsOutsideEveryObservationAndTypedParametersAreLossless()throws Exception {
        var keys=List.of(new KeyValue("k","9007199254740993",KeyValue.Type.DECIMAL));
        for(String mode:List.of("METADATA","COUNT","EXISTS")) {
            var query=SqlServerObservationSql.build("WITH a AS (SELECT CAST(9007199254740993 AS bigint) AS k) SELECT k FROM a",keys,mode);
            assertTrue(query.sql().startsWith("WITH "));assertFalse(query.sql().contains("FROM (WITH"));
            assertTrue(query.sql().contains(mode.equals("METADATA")?"TOP (0)":mode.equals("COUNT")?"COUNT_BIG(*)":"CASE WHEN EXISTS"));
        }
        var statement=mock(PreparedStatement.class);SqlServerObservationSql.bind(statement,List.of(keys.get(0),new KeyValue("text","你好",KeyValue.Type.TEXT),new KeyValue("flag",true,KeyValue.Type.BOOLEAN)));
        verify(statement).setBigDecimal(1,new java.math.BigDecimal("9007199254740993"));verify(statement).setNString(2,"你好");verify(statement).setBoolean(3,true);
    }
    @Test void unsupportedOrderingAndUntypedBindingAreObservable()throws Exception {
        assertThrows(com.bitdata.sqlworkbench.sql.SqlSerializationException.class,()->SqlServerObservationSql.build("SELECT 1 AS k ORDER BY k",List.of(),"METADATA"));
        assertThrows(com.bitdata.sqlworkbench.sql.SqlSerializationException.class,()->SqlServerObservationSql.bind(mock(PreparedStatement.class),List.of(new KeyValue("k",1))));
        assertEquals("[k]]x]",SqlServerObservationSql.quote("k]x"));
    }
}
