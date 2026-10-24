package com.bitdata.sqlworkbench.execution;

import com.bitdata.sqlworkbench.connection.*;
import com.bitdata.sqlworkbench.execution.model.ExecutionStatus;
import com.bitdata.sqlworkbench.sql.SelectOnlyGuard;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ExecutionEngineRetentionTest {
    @Test void activeAndQueuedTasksDoNotAgeOutAndCancellationStartsItsOwnWindow() throws Exception {
        AtomicLong clock = new AtomicLong();
        var service = mock(ConnectionService.class);
        var entered = new CountDownLatch(4);
        var release = new CountDownLatch(1);
        when(service.getById(anyString())).thenAnswer(invocation -> {
            entered.countDown(); release.await(10, TimeUnit.SECONDS); return Optional.empty();
        });
        var engine = new ExecutionEngine(service, mock(ExplainServiceFactory.class), mock(DatabaseStrategyFactory.class),
            mock(SelectOnlyGuard.class),30,100,clock::get);
        try {
            var running = new ArrayList<String>();
            for(int i=0;i<4;i++) running.add(engine.execute("SELECT 1","blocked"));
            assertTrue(entered.await(5,TimeUnit.SECONDS));
            var queued = engine.execute("SELECT 1","queued");
            clock.set(TimeUnit.MINUTES.toNanos(10)); engine.removeExpired();
            assertEquals(ExecutionStatus.QUEUED,engine.getStatus(queued).status());
            for(var id:running) assertEquals(ExecutionStatus.RUNNING,engine.getStatus(id).status());
            assertTrue(engine.cancel(queued));
            assertTrue(engine.cancel(running.get(0)));
            clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(299999)); engine.removeExpired();
            assertEquals(ExecutionStatus.CANCELLED,engine.getStatus(queued).status());
            assertEquals(ExecutionStatus.CANCELLED,engine.getStatus(running.get(0)).status());
            clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(1));engine.removeExpired();
            assertNull(engine.getStatus(queued));assertNull(engine.getStatus(running.get(0)));
        } finally {release.countDown();engine.shutdown();}
    }
    @Test void executorRejectionStillPublishesARetainedFailure() {
        AtomicLong clock=new AtomicLong();var engine=engine(clock);
        engine.shutdown();
        var id=engine.execute("SELECT 1","missing");
        assertEquals(ExecutionStatus.FAILED,engine.getStatus(id).status());
        clock.set(TimeUnit.MILLISECONDS.toNanos(299999));engine.removeExpired();
        assertNotNull(engine.getStatus(id));
        clock.set(TimeUnit.MINUTES.toNanos(5));engine.removeExpired();assertNull(engine.getStatus(id));
    }
    private ExecutionEngine engine(AtomicLong clock) {
        return new ExecutionEngine(mock(ConnectionService.class), mock(ExplainServiceFactory.class),
            mock(DatabaseStrategyFactory.class), mock(SelectOnlyGuard.class), 30, 100, clock::get);
    }
    private void awaitTerminal(ExecutionEngine engine, String id) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!engine.getStatus(id).status().isTerminal() && System.nanoTime() < deadline) Thread.sleep(2);
        assertTrue(engine.getStatus(id).status().isTerminal());
    }
    @Test void terminalResultsSurviveTrafficAndReadsDoNotExtendTheWindow() throws Exception {
        AtomicLong clock = new AtomicLong();
        ExecutionEngine engine = engine(clock);
        try {
            List<String> ids = new ArrayList<>();
            for (int i=0; i<256; i++) {
                String id = i % 2 == 0 ? engine.execute("SELECT 1", "missing") : engine.analyze("SELECT 1", "missing");
                ids.add(id); awaitTerminal(engine,id);
            }
            clock.set(TimeUnit.MILLISECONDS.toNanos(299999)); engine.removeExpired();
            for (String id : ids) assertEquals(ExecutionStatus.FAILED, engine.getStatus(id).status());
            assertThrows(ExecutionCapacityException.class, () -> engine.execute("SELECT 1", "missing"));
            assertThrows(ExecutionCapacityException.class, () -> engine.analyze("SELECT 1", "missing"));
            clock.set(TimeUnit.MINUTES.toNanos(5)); engine.removeExpired();
            for (String id : ids) assertNull(engine.getStatus(id));
            assertNotNull(engine.execute("SELECT 1", "missing"));
        } finally { engine.shutdown(); }
    }
    @Test void concurrentAdmissionNeverExceedsTheRetainedCapacity() throws Exception {
        ExecutionEngine engine = engine(new AtomicLong());
        ExecutorService callers = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Boolean>> calls = new ArrayList<>();
            for (int i=0;i<300;i++) calls.add(() -> {
                try { engine.execute("SELECT 1", "missing"); return true; }
                catch (ExecutionCapacityException rejected) { return false; }
            });
            long accepted = 0;
            for (Future<Boolean> result : callers.invokeAll(calls)) if (result.get()) accepted++;
            assertEquals(256, accepted);
        } finally { callers.shutdownNow(); engine.shutdown(); }
    }
    @Test void bothControllersExplicitlyRejectWithoutAnExecutionReceipt() {
        ExecutionEngine engine = mock(ExecutionEngine.class);
        when(engine.execute(anyString(),anyString(),any(),any(),any())).thenThrow(new ExecutionCapacityException());
        when(engine.analyze(anyString(),anyString(),any(),any(),any(),any())).thenThrow(new ExecutionCapacityException());
        ExecutionController controller = new ExecutionController(engine);
        var request = new com.bitdata.sqlworkbench.execution.model.ExecutionRequest("SELECT 1", "c", null, null, null, null);
        for (var response : List.of(controller.execute(request),controller.analyze(request))) {
            assertEquals(429,response.getStatusCode().value());
            assertEquals("EXECUTION_CAPACITY_EXHAUSTED",((Map<?,?>)response.getBody()).get("code"));
            assertFalse(((Map<?,?>)response.getBody()).containsKey("executionId"));
        }
    }
}
