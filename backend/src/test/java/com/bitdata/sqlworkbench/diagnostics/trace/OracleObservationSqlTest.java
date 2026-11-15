package com.bitdata.sqlworkbench.diagnostics.trace;

import com.bitdata.sqlworkbench.sql.SqlSerializationException;
import org.junit.jupiter.api.Test;
import java.sql.*;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OracleObservationSqlTest {
    @Test void withOrderAndPaginationStayInsideObservedQuery()throws Exception {
        String sql="WITH a AS (SELECT 1 AS K FROM dual UNION ALL SELECT 2 AS K FROM dual) SELECT K FROM a ORDER BY K FETCH FIRST 1 ROWS ONLY";
        var keys=List.of(new KeyValue("K","1",KeyValue.Type.DECIMAL));
        var columns=List.of(new OracleObservationSql.Column("K",Types.NUMERIC));
        for(String mode:List.of("COUNT","EXISTS")) {
            String query=OracleObservationSql.build(sql,columns,keys,List.of("K"),mode).sql();
            assertTrue(query.startsWith("WITH "));assertFalse(query.contains("FROM (WITH"));
            assertFalse(query.contains(") AS \"__observe\""));assertTrue(query.contains("FETCH NEXT 1 ROWS ONLY"));
            assertTrue(query.contains(mode.equals("COUNT")?"COUNT(*)":"CASE WHEN EXISTS"));
        }
        assertTrue(OracleObservationSql.metadata(sql).sql().startsWith("WITH "));
    }
    @Test void actualMetadataControlsBindingAndRefusal()throws Exception {
        var columns=List.of(new OracleObservationSql.Column("N",Types.NUMERIC),new OracleObservationSql.Column("T",Types.NVARCHAR));
        var keys=List.of(new KeyValue("N","9007199254740993.125",KeyValue.Type.DECIMAL),new KeyValue("T","中文",KeyValue.Type.TEXT));
        var statement=mock(PreparedStatement.class);
        OracleObservationSql.build("SELECT 1 N, N'中文' T FROM dual",columns,keys,List.of("N","T"),"COUNT");
        OracleObservationSql.bind(statement,keys,columns,List.of("N","T"));
        verify(statement).setBigDecimal(1,new BigDecimal("9007199254740993.125"));verify(statement).setNString(2,"中文");
        for(var input:List.of(new KeyValue("N",true,KeyValue.Type.BOOLEAN),new KeyValue("N","1",KeyValue.Type.TEXT))) {
            assertEquals("KEY_TYPE_MISMATCH",assertThrows(SqlSerializationException.class,()->OracleObservationSql.build("SELECT 1 N FROM dual",columns,List.of(input),List.of("N"),"COUNT")).reason());
        }
        assertEquals("ORACLE_EMPTY_TEXT_KEY_UNSUPPORTED",assertThrows(SqlSerializationException.class,()->OracleObservationSql.build("SELECT 'a' T FROM dual",columns,List.of(new KeyValue("T","",KeyValue.Type.TEXT)),List.of("T"),"EXISTS")).reason());
        assertEquals("ORACLE_FIXED_CHAR_KEY_UNVERIFIED",assertThrows(SqlSerializationException.class,()->OracleObservationSql.build("SELECT 'a' T FROM dual",List.of(new OracleObservationSql.Column("T",Types.CHAR)),List.of(new KeyValue("T","a",KeyValue.Type.TEXT)),List.of("T"),"COUNT")).reason());
    }
    @Test void bothOracleModesExpireBeforeQueuedWorkStartsAndOldWireStaysAbsent()throws Exception {
        var clock=new java.util.concurrent.atomic.AtomicLong();var store=new TraceStore(clock::get);
        try {
            for(var mode:TraceMode.values()) {
                var request=new TraceRequest("c","{}",List.of(new KeyValue("K","1",KeyValue.Type.DECIMAL)),30,mode,null,List.of());
                String id=store.create(request,"oracle");clock.addAndGet(java.util.concurrent.TimeUnit.SECONDS.toNanos(120));
                assertFalse(store.canExecute(id));store.sealNeverStarted(id,"TEST_QUEUE_EXPIRED");
                var progress=store.toProgress(id);assertEquals("TRACE_BUDGET_EXCEEDED",progress.errorReason());
                assertTrue(progress.lifecycle().settled());assertEquals("NO_RESOURCES",progress.lifecycle().cleanup());
                assertEquals(progress.lifecycle().revision(),store.toProgress(id).lifecycle().revision());
            }
            var request=new TraceRequest("c","{}",List.of(new KeyValue("K",1)),30);
            String id=store.create(request,"postgresql");
            assertFalse(new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(store.toProgress(id)).has("lifecycle"));
        } finally {store.shutdown();}
    }
}
