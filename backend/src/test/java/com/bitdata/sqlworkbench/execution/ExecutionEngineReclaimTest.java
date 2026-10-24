package com.bitdata.sqlworkbench.execution;

import com.bitdata.sqlworkbench.execution.model.ExecutionResult;
import com.bitdata.sqlworkbench.execution.model.ExecutionStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * (D)：回收谓词按**终态**判定（spec §4.3 (D)）。
 *
 * <p>本文件对照的是 **T7 之前**的那版谓词：{@code ExecutionEngine.run} 的
 * {@code finally} 段旧用 {@code result() != null && result().status() ∉
 * {QUEUED, RUNNING}}（最后出现于 {@code 00fb60a}）。它的缺陷是把「可回收」绑在
 * 「每个发布点都记得写 result」这个未强制的约定上：在还只写 status 的 CANCELLED
 * 站点存在时（T5/T6 之前）它**主动泄漏**条目；到 {@code 00fb60a} 那些站点已带
 * result，只剩**潜在脆弱** —— 无实际泄漏，但判据的正确性仍只靠约定撑着。
 *
 * <p>帧要摆正：把该判据改成按终态（{@code isTerminal()}）判定的是 {@code c460e71}
 * （T7），也就是 T8 的 BASE；T8 从 BASE 抽出的 {@code ExecutionEngine.isReclaimable}
 * **本来就是**终态版 —— 本对照**不是** T8 所替换掉的那一版。
 *
 * <p>本测试把两版谓词的差异在同一份 {@code Published} 数据上钉成断言，使
 * 「{@code isReclaimable} 若还含 {@code result != null} 就会红」这句话可复算
 * （不需要旧二进制）。
 */
class ExecutionEngineReclaimTest {

    /** 对照谓词：{@code ExecutionEngine.run} 的 {@code finally} 段在 **T7 之前**所用
     *  的回收判据 —— {@code result != null && result.status() ∉ {QUEUED, RUNNING}}
     *  （最后出现于 {@code 00fb60a}；{@code c460e71} 已把它换成按终态判定，即 T8 的
     *  BASE）。它只在本测试里保留一份作对照，**不是** T8 所替换掉的生产谓词。
     *
     *  <p>形参取 {@code Published}（而不是它的 {@code result}）：对照必须读**同一份
     *  数据** —— 否则「一边喂字面 null、一边喂记录」的对照毫无意义。 */
    private static boolean legacyReclaimable(ExecutionEngine.Published published) {
        ExecutionResult result = published.result();
        return result != null
            && result.status() != ExecutionStatus.QUEUED
            && result.status() != ExecutionStatus.RUNNING;
    }

    private static ExecutionEngine.Published published(ExecutionStatus status, ExecutionResult result) {
        return new ExecutionEngine.Published(status, result);
    }

    @Test
    void cancelledEntryWithoutResultIsReclaimable() {
        ExecutionEngine.Published cancelledWithoutResult = published(ExecutionStatus.CANCELLED, null);

        // 这一条只是对旧谓词的**刻画**（characterization），不是对生产代码的独立
        // 判别器：它的首合取项就是 result != null，而这里 result() 恰为 null，故它
        // 只会在本文件里被改动时才变红。真正的载荷是下一条 assertTrue 与
        // everyTerminalStatusIsReclaimable —— isReclaimable 若还含旧谓词，它们会红。
        assertFalse(legacyReclaimable(cancelledWithoutResult),
            "旧谓词对 (CANCELLED, null) 判「不可回收」—— 这正是泄漏");
        assertTrue(ExecutionEngine.isReclaimable(cancelledWithoutResult),
            "(D): 新谓词必须按终态判定，不依赖 result 是否被写过");
    }

    @Test
    void everyTerminalStatusIsReclaimable() {
        for (ExecutionStatus terminal : new ExecutionStatus[]{
                ExecutionStatus.COMPLETED, ExecutionStatus.FAILED,
                ExecutionStatus.TIMED_OUT, ExecutionStatus.CANCELLED}) {
            assertTrue(ExecutionEngine.isReclaimable(published(terminal, null)),
                terminal + " 是终态，条目必须可回收");
        }
    }

    @Test
    void nonTerminalAndUnpublishedEntriesAreNotReclaimable() {
        assertFalse(ExecutionEngine.isReclaimable(published(ExecutionStatus.QUEUED, null)));
        assertFalse(ExecutionEngine.isReclaimable(published(ExecutionStatus.RUNNING, null)));
        assertFalse(ExecutionEngine.isReclaimable(published(null, null)),
            "还没发布过任何状态的条目不得被回收");
    }
}
