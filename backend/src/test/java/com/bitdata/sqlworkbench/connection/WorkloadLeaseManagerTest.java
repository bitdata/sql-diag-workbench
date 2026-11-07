package com.bitdata.sqlworkbench.connection;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorkloadLeaseManagerTest {
    @Test
    void exclusiveTicketRejectsNewOrdinaryWorkAndReleasesOnce() throws Exception {
        WorkloadLeaseManager manager=new WorkloadLeaseManager();
        try {
            var exclusive=manager.acquireExclusive();
            assertThrows(WorkloadBusyException.class,manager::acquireOrdinary);
            assertTrue(manager.snapshot().exclusiveActive());
            exclusive.release();exclusive.release();
            assertFalse(manager.snapshot().exclusiveActive());
        } finally { manager.close(); }
    }

    @Test
    void ordinaryWorkPreventsExclusiveAdmissionUntilEveryTicketCloses() throws Exception {
        WorkloadLeaseManager manager=new WorkloadLeaseManager();
        try {
            var first=manager.acquireOrdinary();var second=manager.acquireOrdinary();
            assertEquals(2,manager.snapshot().ordinaryTickets());
            assertThrows(WorkloadBusyException.class,manager::acquireExclusive);
            first.release();
            assertThrows(WorkloadBusyException.class,manager::acquireExclusive);
            second.release();
            var exclusive=manager.acquireExclusive();
            assertNotNull(exclusive);
            exclusive.release();
        } finally { manager.close(); }
    }

    @Test
    void closeFailureKeepsLeaseUntilRetryConfirmsClose() throws Exception {
        WorkloadLeaseManager manager=new WorkloadLeaseManager();
        try {
            var ticket=manager.acquireOrdinary();Connection physical=mock(Connection.class);AtomicInteger closes=new AtomicInteger();
            org.mockito.Mockito.doAnswer(invocation->{if(closes.incrementAndGet()==1)throw new SQLException("injected close failure");return null;}).when(physical).close();
            when(physical.isClosed()).thenReturn(false);
            Connection leased=manager.track(physical,ticket);
            assertThrows(SQLException.class,leased::close);
            assertEquals(1,manager.snapshot().ordinaryTickets());
            assertEquals(1,manager.snapshot().unconfirmedTickets());
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);
            while(manager.snapshot().ordinaryTickets()!=0&&System.nanoTime()<deadline)Thread.sleep(20);
            assertEquals(0,manager.snapshot().ordinaryTickets());
            assertTrue(closes.get()>=2);
        } finally { manager.close(); }
    }

    @Test
    void runtimeCloseFailureKeepsRetainedLeaseUntilRetryConfirmsClose() throws Exception {
        WorkloadLeaseManager manager=new WorkloadLeaseManager();
        var taskTicket=manager.acquireExclusive();
        try {
            var connectionTicket=taskTicket.retain();
            Connection physical=mock(Connection.class);AtomicInteger closes=new AtomicInteger();
            org.mockito.Mockito.doAnswer(invocation->{if(closes.incrementAndGet()==1)throw new IllegalStateException("injected runtime close failure");return null;}).when(physical).close();
            when(physical.isClosed()).thenReturn(false);
            Connection leased=manager.track(physical,connectionTicket);

            assertThrows(IllegalStateException.class,leased::close);
            taskTicket.release();
            assertTrue(manager.snapshot().exclusiveActive());
            assertEquals(1,manager.snapshot().unconfirmedTickets());

            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);
            while(manager.snapshot().exclusiveActive()&&System.nanoTime()<deadline)Thread.sleep(20);
            assertFalse(manager.snapshot().exclusiveActive());
            assertTrue(closes.get()>=2);
        } finally { taskTicket.release(); manager.close(); }
    }

    @Test
    void shutdownCleanupExecutorKeepsCloseFailureObservableAndLeaseUnconfirmed() throws Exception {
        WorkloadLeaseManager manager=new WorkloadLeaseManager();
        try {
            var ticket=manager.acquireOrdinary();Connection physical=mock(Connection.class);
            org.mockito.Mockito.doThrow(new SQLException("injected close failure")).when(physical).close();
            when(physical.isClosed()).thenReturn(false);
            Connection leased=manager.track(physical,ticket);
            manager.close();

            assertEquals("injected close failure",assertThrows(SQLException.class,leased::close).getMessage());
            assertEquals(1,manager.snapshot().ordinaryTickets());
            assertEquals(1,manager.snapshot().unconfirmedTickets());
            assertEquals("CONNECTION_CLEANUP_UNCONFIRMED",ticket.errorReason());
        } finally { manager.close(); }
    }
}
