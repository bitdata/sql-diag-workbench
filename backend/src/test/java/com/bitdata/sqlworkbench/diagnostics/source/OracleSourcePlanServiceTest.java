package com.bitdata.sqlworkbench.diagnostics.source;

import com.bitdata.sqlworkbench.connection.DatabaseStrategyFactory;
import com.bitdata.sqlworkbench.diagnostics.trace.*;
import com.bitdata.sqlworkbench.sql.*;
import org.junit.jupiter.api.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OracleSourcePlanServiceTest {
    final SqlDialectResolver resolver=new SqlDialectResolver(DatabaseStrategyFactory.withBuiltinStrategies());
    final OracleSourcePlanService service=new OracleSourcePlanService(resolver,new SelectOnlyGuard(resolver));
    Connection connection; boolean compound; int resultPrecision=10;
    final PreparationJdbc task=new PreparationJdbc(){public void checkpoint(){}public void configure(Statement statement)throws SQLException{statement.setQueryTimeout(5);}public void clearStatement(){}};
    @BeforeEach void setup()throws Exception {
        connection=mock(Connection.class);
        when(connection.prepareStatement(anyString())).thenAnswer(i->{
            String sql=i.getArgument(0);var statement=mock(PreparedStatement.class);Map<Integer,String> bound=new HashMap<>();
            doAnswer(a->{bound.put(a.getArgument(0),a.getArgument(1));return null;}).when(statement).setString(anyInt(),anyString());
            when(statement.executeQuery()).thenAnswer(a->{
                if(sql.contains("SYS_CONTEXT"))return rows(List.of(List.of("CURRENT_OWNER","CURRENT_OWNER")));
                if(sql.contains("all_objects"))return rows(List.of(List.of("V".equals(bound.get(2))?"VIEW":"TABLE","VALID")));
                if(sql.contains("all_tab_columns"))return rows(compound?List.of(List.of("ID","NUMBER","10","0"),List.of("LINE_ID","NUMBER","10","0")):List.of(List.of("ID","NUMBER","10","0")));
                if(sql.contains("all_constraints"))return rows(compound?List.of(List.of("ID"),List.of("LINE_ID")):List.of(List.of("ID")));
                var meta=mock(ResultSetMetaData.class);var result=mock(ResultSet.class);when(result.getMetaData()).thenReturn(meta);
                int count=sql.contains("source_key_1")||sql.contains("\"LINE_ID\"")?2:1;when(meta.getColumnCount()).thenReturn(count);
                for(int n=1;n<=count;n++) {
                    when(meta.getColumnLabel(n)).thenReturn(sql.contains("source_key_")?"source_key_"+(n-1):n==1?"ID":"LINE_ID");
                    when(meta.getColumnType(n)).thenReturn(Types.NUMERIC);when(meta.getPrecision(n)).thenReturn(sql.contains("source_key_")?10:resultPrecision);when(meta.getScale(n)).thenReturn(0);
                }
                return result;
            });return statement;
        });
    }
    static ResultSet rows(List<List<String>> values)throws SQLException {
        var result=mock(ResultSet.class);var meta=mock(ResultSetMetaData.class);AtomicInteger index=new AtomicInteger(-1);
        when(result.next()).thenAnswer(i->index.incrementAndGet()<values.size());when(result.getMetaData()).thenReturn(meta);
        when(meta.getColumnCount()).thenReturn(values.isEmpty()?0:values.get(0).size());when(result.getString(anyInt())).thenAnswer(i->values.get(index.get()).get((int)i.getArgument(0)-1));return result;
    }
    PhysicalSourcePlanService.Request request(String sql,String...labels) {
        return new PhysicalSourcePlanService.Request("c",sql,Arrays.stream(labels).map(label->new KeyValue(label,"1",KeyValue.Type.DECIMAL)).toList());
    }
    @Test void numericCompletePrimaryKeyAndMetadataAgreeBeforeCertification()throws Exception {
        var plan=service.inspect(request("SELECT ID FROM ORDERS","ID"),true,connection,task);
        assertTrue(plan.verified());assertEquals(1,plan.sources().size());
        assertTrue(plan.sources().get(0).sourceSql().contains("\"CURRENT_OWNER\".\"ORDERS\" \"src\""));
        assertEquals("source_key_0",plan.sources().get(0).keys().get(0).column());
        verify(connection,never()).getMetaData();
        resultPrecision=9;
        var mismatch=service.inspect(request("SELECT ID FROM ORDERS","ID"),true,connection,task);
        assertFalse(mismatch.verified());assertTrue(mismatch.sources().isEmpty());assertTrue(mismatch.issues().stream().anyMatch(i->i.errorReason().equals("SOURCE_RESULT_TYPE_MISMATCH")));
    }
    @Test void partialCompoundKeyCannotBecomeAnExecutableSource()throws Exception {
        compound=true;
        var partial=service.inspect(request("SELECT ID FROM ORDERS","ID"),true,connection,task);
        assertTrue(partial.sources().isEmpty());assertTrue(partial.issues().stream().anyMatch(i->i.errorReason().equals("SOURCE_COMPLETE_PRIMARY_KEY_REQUIRED")));
        var complete=service.inspect(request("SELECT ID, LINE_ID FROM ORDERS","ID","LINE_ID"),true,connection,task);
        assertTrue(complete.verified());assertEquals(2,complete.sources().get(0).keys().size());
    }
    @Test void unknownViewDoesNotEraseCertifiedSiblingAndNeverGetsSourceSql()throws Exception {
        var plan=service.inspect(request("SELECT o.ID FROM ORDERS o JOIN V v ON o.ID=v.ID","ID"),false,connection,task);
        assertEquals("PARTIAL",plan.status());assertFalse(plan.verified());assertEquals(1,plan.sources().size());
        assertEquals("ORDERS",plan.sources().get(0).relation());assertTrue(plan.issues().stream().anyMatch(i->"V".equals(i.relation())&&i.errorReason().equals("ORACLE_SOURCE_NON_TABLE_UNVERIFIED")));
    }
}
