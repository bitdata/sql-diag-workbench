package com.bitdata.sqlworkbench.diagnostics.trace;

import com.bitdata.sqlworkbench.diagnostics.DiagnosticFinding;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TraceStoreTest {

    private TraceStep step(int idx, String id, TraceStatus status) {
        return new TraceStep(idx, id, "Label-" + id, "", status, -1, 10, null);
    }

    private TraceRequest req(String fragmentTreeJson) {
        return new TraceRequest("c1", fragmentTreeJson,
            List.of(new KeyValue("id", 1)), 30);
    }

    @Test
    void toProgress_carriesOutcomeAndErrorReason() {
        TraceStore store = new TraceStore();
        String traceId = store.create(req("{}"));

        TraceStep parentStep = step(1, "root", TraceStatus.NO_ROWS);
        EliminationOutcome.Edge outcome = new EliminationOutcome.Edge(
            step(0, "leaf", TraceStatus.HAS_ROWS), parentStep);

        store.complete(traceId, parentStep, List.<DiagnosticFinding>of(), outcome, null);

        TraceProgress progress = store.toProgress(traceId);
        assertNotNull(progress);
        assertNotNull(progress.eliminationOutcome(),
            "eliminationOutcome must be exposed on TraceProgress");
        assertEquals("EDGE", progress.eliminationOutcome().outcomeType());
        assertNull(progress.errorReason());
        assertEquals(TraceStatus.COMPLETED, progress.status());
    }

    @Test
    void toProgress_carriesErrorReason_whenErrorOutcome() {
        TraceStore store = new TraceStore();
        String traceId = store.create(req("{}"));

        TraceStep errStep = step(0, "leaf", TraceStatus.ERROR);
        EliminationOutcome.Error outcome = new EliminationOutcome.Error(errStep, "boom");

        store.complete(traceId, null, List.<DiagnosticFinding>of(), outcome, "boom");

        TraceProgress progress = store.toProgress(traceId);
        assertNotNull(progress);
        assertEquals("ERROR", progress.eliminationOutcome().outcomeType());
        assertEquals("boom", progress.errorReason());
    }

    @Test
    void toProgress_outcomeIsNull_whenCompleteCalledWithoutOutcome() {
        // Backward-compat: legacy callers (or future null outcome) leave fields null.
        TraceStore store = new TraceStore();
        String traceId = store.create(req("{}"));

        store.complete(traceId, null, List.<DiagnosticFinding>of(), null, null);

        TraceProgress progress = store.toProgress(traceId);
        assertNotNull(progress);
        assertNull(progress.eliminationOutcome());
        assertNull(progress.errorReason());
    }

    // ── 终态闩锁（规则 #1 同类收口）──────────────────────────────────────────
    // 缺陷类：对已终态 trace 的 publish / cancel 无条件覆盖终态。执行引擎一侧
    // 已由 ExecutionEngineTerminalLatchTest 收口；同一类在 TraceStore 的
    // setRunning / complete / fail / cancel 四个发布站点上原样存活，本组钉住。

    @Test
    void cancel_onATerminalTraceIsANoOpAndLeavesTheTerminalStatusAlone() {
        TraceStore store = new TraceStore();
        String traceId = store.create(req("{}"));
        store.setRunning(traceId);
        store.complete(traceId, null, List.<DiagnosticFinding>of(), null, null);

        // 旧实现：cancel 对任何存在的 trace 都置 CANCELLED 并返回 true ⇒ 改写
        // COMPLETED、且不带原因 ⇒ 本断言红。
        assertFalse(store.cancel(traceId), "cancel 对已终态 trace 必须报告未生效");
        TraceProgress progress = store.toProgress(traceId);
        assertEquals(TraceStatus.COMPLETED, progress.status());
        assertNull(progress.error(), "cancel 不得给已完成的 trace 写入取消原因");
    }

    @Test
    void lateCompleteCannotOverwriteACancelledOutcome() {
        TraceStore store = new TraceStore();
        String traceId = store.create(req("{}"));
        store.setRunning(traceId);
        assertTrue(store.cancel(traceId));

        // worker 在取消之后才落 complete：旧实现无条件覆盖 ⇒ 红。
        store.complete(traceId, step(0, "leaf", TraceStatus.HAS_ROWS),
            List.<DiagnosticFinding>of(), null, "late");

        TraceProgress progress = store.toProgress(traceId);
        assertEquals(TraceStatus.CANCELLED, progress.status(),
            "晚到的 complete 不得覆盖已取消的终态");
    }

    @Test
    void lateFailCannotOverwriteACancelledOutcome() {
        TraceStore store = new TraceStore();
        String traceId = store.create(req("{}"));
        store.setRunning(traceId);
        assertTrue(store.cancel(traceId));

        // worker 的 catch 在取消之后才落 fail：旧实现无条件覆盖 ⇒ 红。
        store.fail(traceId, "boom");

        TraceProgress progress = store.toProgress(traceId);
        assertEquals(TraceStatus.CANCELLED, progress.status(),
            "晚到的 fail 不得覆盖已取消的终态");
    }

    @Test
    void cancel_onARunningTracePublishesCancelledWithAReason() {
        TraceStore store = new TraceStore();
        String traceId = store.create(req("{}"));
        store.setRunning(traceId);

        assertTrue(store.cancel(traceId), "取消一个运行中的 trace 必须报告生效");

        TraceProgress progress = store.toProgress(traceId);
        assertEquals(TraceStatus.CANCELLED, progress.status());
        // 旧实现只写 status、不写 error ⇒ 终态无原因 ⇒ 红。
        assertNotNull(progress.error(), "CANCELLED 终态必须带可读原因");
        assertEquals("Cancelled by user request", progress.error());
    }

    @Test
    void setRunningAfterCancelDoesNotResurrectTheTrace() {
        TraceStore store = new TraceStore();
        String traceId = store.create(req("{}"));
        // 取消发生在 worker 调 setRunning 之前（提交后、线程调度前）。
        assertTrue(store.cancel(traceId));

        store.setRunning(traceId);

        // 旧实现 setRunning 无条件写 RUNNING ⇒ 把已取消的 trace 复活成 RUNNING，
        // 而 worker 之后因 `cancelled` 跳过 complete ⇒ 永远停在 RUNNING ⇒ 红。
        assertEquals(TraceStatus.CANCELLED, store.toProgress(traceId).status(),
            "取消之后 setRunning 不得把终态改回 RUNNING");
    }

    @Test
    void lateCancelCannotOverwriteACompletedOutcome() {
        TraceStore store = new TraceStore();
        String traceId = store.create(req("{}"));
        store.setRunning(traceId);
        store.complete(traceId, null, List.<DiagnosticFinding>of(), null, null);

        assertFalse(store.cancel(traceId));
        assertEquals(TraceStatus.COMPLETED, store.toProgress(traceId).status(),
            "先到终态者胜；晚到的 cancel 不得覆盖 COMPLETED");
    }

    @Test
    void cancel_onAnUnknownTraceReturnsFalse() {
        assertFalse(new TraceStore().cancel("never-existed"));
    }
}