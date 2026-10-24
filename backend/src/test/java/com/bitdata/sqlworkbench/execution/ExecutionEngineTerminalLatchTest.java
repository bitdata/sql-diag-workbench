package com.bitdata.sqlworkbench.execution;

import com.bitdata.sqlworkbench.execution.model.ExecutionResult;
import com.bitdata.sqlworkbench.execution.model.ExecutionStatus;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * (C) 终态闩锁 + (B) cancel 入口站的发布完整性（spec 2026-10-01 §4.3）。
 *
 * <p>为什么用反射：{@code ExecutionContext} 是 private 静态嵌套类，且这些语义是
 * 「发布状态机」而非「跑 SQL」——不需要数据库就能确定性钉住。
 * 与 {@link ExecutionEnginePublishShapeTest} 同一手法（那支钉字段名，这支钉语义）。
 */
class ExecutionEngineTerminalLatchTest {
    @Test void selectWinnerSavesZeroRowsOnceAndCancelledWinnerSavesNothing() throws Exception {
        var engine=newEngine();
        var store=org.mockito.Mockito.mock(com.bitdata.sqlworkbench.history.HistoryStore.class);
        org.springframework.test.util.ReflectionTestUtils.setField(engine,"historyStore",store);
        var ctx=newContext("e-history");
        org.springframework.test.util.ReflectionTestUtils.setField(ctx,"sessionId","owner");
        var result=new ExecutionResult("e-history",ExecutionStatus.COMPLETED,null,java.util.List.of(),0,new ExecutionResult.Timing(0),null,null,null);
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(engine,"completeSelect",ctx,result);
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(engine,"completeSelect",ctx,result);
        assertEquals("saved",resultOf(ctx).historyPersistence().status());
        org.mockito.Mockito.verify(store).save(org.mockito.ArgumentMatchers.argThat(e -> e.id().equals("e-history") && e.sessionId().equals("owner") && e.rowCount()==0 && e.durationMs()==0));
        var cancelled=newContext("e-cancel");
        org.springframework.test.util.ReflectionTestUtils.setField(cancelled,"sessionId","owner");
        publish(cancelled,ExecutionStatus.CANCELLED,cancelled("stop"));
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(engine,"completeSelect",cancelled,result);
        org.mockito.Mockito.verifyNoMoreInteractions(store);
    }
    @Test void historyFailurePreservesSuccessfulQueryAndExposesFailure() throws Exception {
        var engine=newEngine(); var ctx=newContext("e-fail");
        org.springframework.test.util.ReflectionTestUtils.setField(ctx,"sessionId","owner");
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(engine,"completeSelect",ctx,
            new ExecutionResult("e-fail",ExecutionStatus.COMPLETED,null,java.util.List.of(),0,new ExecutionResult.Timing(0),null,null,null));
        assertEquals(ExecutionStatus.COMPLETED,statusOf(ctx));
        assertEquals("failed",resultOf(ctx).historyPersistence().status());
        assertEquals("HISTORY_SAVE_FAILED",resultOf(ctx).historyPersistence().errorReason());
    }

    private static ExecutionEngine newEngine() {
        // 各依赖在本组用例里都不被触碰：只走 publish / cancel 的状态机。
        return new ExecutionEngine(null, null, null, null, 30, 100);
    }

    private static Object newContext(String executionId) throws Exception {
        Class<?> kindClass = Class.forName("com.bitdata.sqlworkbench.execution.ExecutionEngine$Kind");
        @SuppressWarnings({"unchecked", "rawtypes"})
        Object executeKind = Enum.valueOf((Class<? extends Enum>) kindClass, "EXECUTE");
        Class<?> ctxClass = Class.forName("com.bitdata.sqlworkbench.execution.ExecutionEngine$ExecutionContext");
        Constructor<?> ctor = ctxClass.getDeclaredConstructor(
            String.class, String.class, String.class, kindClass, String.class);
        ctor.setAccessible(true);
        return ctor.newInstance(executionId, "SELECT 1", "conn-1", executeKind, null);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> executionsOf(ExecutionEngine engine) throws Exception {
        Field f = ExecutionEngine.class.getDeclaredField("executions");
        f.setAccessible(true);
        return (Map<String, Object>) f.get(engine);
    }

    private static void publish(Object ctx, ExecutionStatus status, ExecutionResult result) throws Exception {
        Method m = ctx.getClass().getDeclaredMethod("publish", ExecutionStatus.class, ExecutionResult.class);
        m.setAccessible(true);
        m.invoke(ctx, status, result);
    }

    private static ExecutionStatus statusOf(Object ctx) throws Exception {
        Field f = ctx.getClass().getDeclaredField("published");
        f.setAccessible(true);
        Object published = f.get(ctx);
        Method status = published.getClass().getDeclaredMethod("status");
        status.setAccessible(true);
        return (ExecutionStatus) status.invoke(published);
    }

    private static ExecutionResult resultOf(Object ctx) throws Exception {
        Field f = ctx.getClass().getDeclaredField("published");
        f.setAccessible(true);
        Object published = f.get(ctx);
        Method result = published.getClass().getDeclaredMethod("result");
        result.setAccessible(true);
        return (ExecutionResult) result.invoke(published);
    }

    private static ExecutionResult completed() {
        return new ExecutionResult("e", ExecutionStatus.COMPLETED, null, null, 0, null, null, null, null);
    }

    private static ExecutionResult failure(String message) {
        return new ExecutionResult("e", ExecutionStatus.FAILED, null, null, 0, null, null, null, message);
    }

    private static ExecutionResult cancelled(String reason) {
        return new ExecutionResult("e", ExecutionStatus.CANCELLED, null, null, 0, null, null, null, reason);
    }

    @Test
    void nonTerminalPublishesStillAdvanceTheState() throws Exception {
        Object ctx = newContext("e-advance");
        publish(ctx, ExecutionStatus.QUEUED, null);
        assertEquals(ExecutionStatus.QUEUED, statusOf(ctx));
        publish(ctx, ExecutionStatus.RUNNING, null);
        assertEquals(ExecutionStatus.RUNNING, statusOf(ctx),
            "闩锁只能锁终态，不能把 QUEUED→RUNNING 一起锁死");
    }

    @Test
    void lateFailureDoesNotOverwriteACancelledOutcome() throws Exception {
        Object ctx = newContext("e-cancel-first");
        publish(ctx, ExecutionStatus.CANCELLED, cancelled("Cancelled by user request"));
        publish(ctx, ExecutionStatus.FAILED, failure("canceling statement due to user request"));
        // spec §4.3 (C)：worker 不得用 FAILED 覆盖已发布的 CANCELLED。旧实现覆盖 ⇒ 红。
        assertEquals(ExecutionStatus.CANCELLED, statusOf(ctx));
    }

    @Test
    void lateCancelledDoesNotOverwriteACompletedOutcome() throws Exception {
        Object ctx = newContext("e-complete-first");
        publish(ctx, ExecutionStatus.COMPLETED, completed());
        publish(ctx, ExecutionStatus.CANCELLED, cancelled("Cancelled by user request"));
        assertEquals(ExecutionStatus.COMPLETED, statusOf(ctx), "先到终态者胜");
    }

    @Test
    void cancelOnATerminalExecutionReturnsFalseAndLeavesItAlone() throws Exception {
        ExecutionEngine engine = newEngine();
        Object ctx = newContext("e-terminal");
        publish(ctx, ExecutionStatus.COMPLETED, completed());
        executionsOf(engine).put("e-terminal", ctx);

        // spec §4.3 (C)：对已终态执行 cancel() 返回 false。旧实现返回 true 并把
        // COMPLETED 改写成 CANCELLED ⇒ 红。
        assertFalse(engine.cancel("e-terminal"));
        assertEquals(ExecutionStatus.COMPLETED, statusOf(ctx));
        assertEquals(ExecutionStatus.COMPLETED, resultOf(ctx).status());
    }

    @Test
    void cancelOnARunningExecutionPublishesCancelledWithAReason() throws Exception {
        ExecutionEngine engine = newEngine();
        Object ctx = newContext("e-running");
        publish(ctx, ExecutionStatus.RUNNING, null);
        executionsOf(engine).put("e-running", ctx);

        assertTrue(engine.cancel("e-running"));
        assertEquals(ExecutionStatus.CANCELLED, statusOf(ctx));
        ExecutionResult published = resultOf(ctx);
        // spec §4.3 (B) 入口站：旧实现 publish(CANCELLED, null)，getStatus 回落到
        // 一个 error==null 的终态 ⇒ 红。
        assertNotNull(published, "CANCELLED 必须发完整终态 result，不能只写 status");
        assertEquals(ExecutionStatus.CANCELLED, published.status());
        assertNotNull(published.error(), "CANCELLED 必须带取消原因");
        assertEquals("Cancelled by user request", published.error());
    }

    @Test
    void cancelOnUnknownExecutionStillReturnsFalse() throws Exception {
        assertFalse(newEngine().cancel("never-existed"));
    }

    @Test
    void aConcurrentWorkerWinningTheLatchMakesCancelReturnFalse() throws Exception {
        ExecutionEngine engine = newEngine();
        Object ctx = newContext("e-race");

        // Deterministic witness of the cancel/worker race: cancel() reaches
        // ctx.future.cancel(true) with the latch still open (it passed the unlocked
        // pre-check), and the worker publishes its terminal outcome in that window.
        // Injecting the worker's publish at future.cancel(true) is faithful to that
        // window; unlike a real two-thread race, it is deterministic.
        Field futureField = ctx.getClass().getDeclaredField("future");
        futureField.setAccessible(true);
        futureField.set(ctx, new Future<Object>() {
            @Override public boolean cancel(boolean mayInterruptIfRunning) {
                try {
                    publish(ctx, ExecutionStatus.COMPLETED, completed());
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
                return true;
            }
            @Override public boolean isCancelled() { return false; }
            @Override public boolean isDone() { return true; }
            @Override public Object get() { return null; }
            @Override public Object get(long timeout, TimeUnit unit) { return null; }
        });

        publish(ctx, ExecutionStatus.RUNNING, null);
        executionsOf(engine).put("e-race", ctx);

        // Old implementation returned true unconditionally -> red here.
        assertFalse(engine.cancel("e-race"),
            "cancel lost the latch to a concurrently-completing worker, so it must not report success");
        assertEquals(ExecutionStatus.COMPLETED, statusOf(ctx),
            "worker's terminal outcome stands; cancel did not overwrite it");
    }
}
