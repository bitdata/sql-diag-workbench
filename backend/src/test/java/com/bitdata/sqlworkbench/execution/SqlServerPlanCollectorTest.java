package com.bitdata.sqlworkbench.execution;

import org.junit.jupiter.api.Test;
import java.sql.*;
import java.io.StringReader;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class SqlServerPlanCollectorTest {
    @Test void actualDrainsBusinessRowsEvenWhenOutputSpoofsXmlLabelThenReadsTrailingPlan() throws Exception {
        var c=mock(Connection.class);var setup=mock(Statement.class);var query=mock(Statement.class);
        when(c.createStatement()).thenReturn(setup,query);
        var business=mock(ResultSet.class);var remaining=new AtomicInteger(2001);when(business.next()).thenAnswer(i->remaining.getAndDecrement()>0);
        var xml=mock(ResultSet.class);var meta=mock(ResultSetMetaData.class);
        when(xml.getMetaData()).thenReturn(meta);when(meta.getColumnCount()).thenReturn(1);
        when(meta.getColumnLabel(1)).thenReturn("Microsoft SQL Server 2005 XML Showplan");
        when(xml.next()).thenReturn(true,false);when(xml.getCharacterStream(1)).thenReturn(new StringReader(SqlServerPlanParserTest.PLAN));
        when(query.execute("SELECT 1")).thenReturn(true);
        when(query.getResultSet()).thenReturn(business,xml);when(query.getMoreResults(Statement.CLOSE_CURRENT_RESULT)).thenReturn(true,false);
        when(query.getUpdateCount()).thenReturn(-1);
        var checkpoints=new AtomicInteger();
        var out=SqlServerPlanCollector.capture(c,"SELECT 1",true,()->30,checkpoints::incrementAndGet,s->{},SqlServerPlanCollector::closeResources);
        assertEquals("COMPLETE",out.status());assertEquals(1,out.documents().size());
        assertTrue(checkpoints.get()<20,"Owner validation must not perform local-store reads per business row");
        assertTrue(remaining.get()<0,"Complete business stream must be drained");
        verify(business,never()).getCharacterStream(anyInt());verify(query,never()).setMaxRows(anyInt());
        verify(query).execute("SELECT 1");verify(setup).execute("SET STATISTICS XML ON");verify(setup).execute("SET STATISTICS XML OFF");verify(c).close();
    }
    @Test void transportLossClosesWithoutSendingOff() throws Exception {
        var c=mock(Connection.class);var setup=mock(Statement.class);var query=mock(Statement.class);
        when(c.createStatement()).thenReturn(setup,query);
        when(query.execute(anyString())).thenThrow(new SQLException("lost","08S01"));
        assertThrows(SQLException.class,()->SqlServerPlanCollector.capture(c,"SELECT 1",true,()->30,()->{},s->{},SqlServerPlanCollector::closeResources));
        verify(setup,never()).execute("SET STATISTICS XML OFF");verify(c).close();
    }
    @Test void offFailureIsExplicitAndNoPlanIsNotFabricated() throws Exception {
        var c=mock(Connection.class);var setup=mock(Statement.class);var query=mock(Statement.class);
        when(c.createStatement()).thenReturn(setup,query);when(query.execute(anyString())).thenReturn(false);when(query.getUpdateCount()).thenReturn(-1);
        doThrow(new SQLException("off failed")).when(setup).execute("SET STATISTICS XML OFF");
        var out=SqlServerPlanCollector.capture(c,"SELECT 1",true,()->30,()->{},s->{},SqlServerPlanCollector::closeResources);
        assertEquals("PARTIAL",out.status());assertEquals("PLAN_CLEANUP_FAILED",out.errorReason());assertTrue(out.documents().isEmpty());verify(c).close();
    }
}
