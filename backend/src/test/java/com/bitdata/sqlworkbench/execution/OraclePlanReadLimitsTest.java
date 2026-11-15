package com.bitdata.sqlworkbench.execution;

import org.junit.jupiter.api.Test;
import java.io.*;
import java.math.BigDecimal;
import java.sql.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class OraclePlanReadLimitsTest {
    @Test void oracleNumberDoesNotUseUnsupportedCharacterStreamOrLosePrecision() throws Exception {
        var rs=mock(ResultSet.class);var metadata=mock(ResultSetMetaData.class);
        when(rs.getMetaData()).thenReturn(metadata);when(metadata.getColumnType(1)).thenReturn(Types.NUMERIC);
        when(rs.getBigDecimal(1)).thenReturn(new BigDecimal("9007199254740993.123456789"));
        when(rs.getCharacterStream(anyInt())).thenThrow(new SQLException("Unsupported conversion",null,17004));
        assertEquals("9007199254740993.123456789",OraclePlanReadLimits.cell(rs,1,1024,4096,"LIMIT",()->{}));
        verify(rs,never()).getCharacterStream(anyInt());
    }
    @Test void oversizedReaderStopsAfterLimitPlusOneAndCloses() throws Exception {
        var count=new AtomicInteger();var closed=new AtomicInteger();
        Reader reader=new Reader(){public int read(char[] b,int off,int len){java.util.Arrays.fill(b,off,off+len,'x');count.addAndGet(len);return len;}public void close(){closed.incrementAndGet();}};
        assertEquals("LIMIT",assertThrows(OraclePlanReadLimits.Limited.class,()->OraclePlanReadLimits.text(reader,1024,4096,"LIMIT",()->{})).getMessage());
        assertEquals(1025,count.get());assertEquals(1,closed.get());
    }
    @Test void utf8BudgetAndMalformedSurrogatesAreRejected() {
        assertEquals("LIMIT",assertThrows(OraclePlanReadLimits.Limited.class,()->OraclePlanReadLimits.text(new StringReader("计".repeat(100)),100,299,"LIMIT",()->{})).getMessage());
        assertEquals("MALFORMED_EVIDENCE_TEXT",assertThrows(OraclePlanReadLimits.Limited.class,()->OraclePlanReadLimits.text(new StringReader("\uD800"),10,40,"LIMIT",()->{})).getMessage());
    }
    @Test void cancellationAfterBlockingReadClosesReaderBeforeReturning() {
        var checks=new AtomicInteger();var closed=new AtomicInteger();
        var reader=new StringReader("x"){@Override public void close(){closed.incrementAndGet();super.close();}};
        assertThrows(CancellationException.class,()->OraclePlanReadLimits.text(reader,10,40,"LIMIT",()->{if(checks.incrementAndGet()==2)throw new CancellationException();}));
        assertEquals(1,closed.get());
    }
    @Test void fullSqlRequiresExactlyOneUntruncatedCursorRow() throws Exception {
        var rs=mock(ResultSet.class);when(rs.next()).thenReturn(true,false);when(rs.getCharacterStream(1)).thenReturn(new StringReader("x".repeat(5001)));
        assertEquals(5001,OraclePlanReadLimits.fullSql(rs,()->{}).length());
        var multiple=mock(ResultSet.class);when(multiple.next()).thenReturn(true);when(multiple.getCharacterStream(1)).thenReturn(new StringReader("x"));
        assertEquals("MULTIPLE_CURSOR_ROWS",assertThrows(OraclePlanReadLimits.Limited.class,()->OraclePlanReadLimits.fullSql(multiple,()->{})).getMessage());
    }
    @Test void businessDrainNeverMaterializesCellsAndRequiresEof() throws Exception {
        var rs=mock(ResultSet.class);when(rs.next()).thenReturn(true,true,false);
        assertEquals(2,OraclePlanReadLimits.drain(rs,()->{}));verify(rs,never()).getObject(anyInt());verify(rs,never()).getCharacterStream(anyInt());
        ResultSet endless=(ResultSet)java.lang.reflect.Proxy.newProxyInstance(ResultSet.class.getClassLoader(),new Class<?>[]{ResultSet.class},(p,m,a)->{if(m.getName().equals("next"))return true;throw new AssertionError("Unexpected cell access");});
        assertEquals("DATA_STREAM_LIMIT_EXCEEDED",assertThrows(OraclePlanReadLimits.Limited.class,()->OraclePlanReadLimits.drain(endless,()->{})).getMessage());
    }
}
