package com.bitdata.sqlworkbench.execution;

import com.bitdata.sqlworkbench.connection.ConnectionService;
import com.bitdata.sqlworkbench.execution.model.ExecutionResult;
import com.bitdata.sqlworkbench.execution.model.ExecutionStatus;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 规则 #1 同类扫描的第 5 个「终态发布不完备」站点（spec §4.3 未列，T14 回写）。
 *
 * <p>可达路径：connectionId 在提交与执行之间被删除 ⇒ runSelect 里
 * publish(RUNNING) 之后、try-with-resources 之前的 lookupDialect 抛
 * IllegalStateException ⇒ 执行永远停在 RUNNING。旧实现上本用例因
 * 「5s 内没到终态」红。
 */
class ExecutionEngineRunFallbackTest {

    private static ExecutionEngine engineWithNoSuchConnection() {
        ConnectionService connections = mock(ConnectionService.class);
        when(connections.getById(anyString())).thenReturn(Optional.empty());
        return new ExecutionEngine(connections, null, null, null, 30, 100);
    }

    @Test
    void executionWithAVanishedConnectionReachesTerminalFailed() throws Exception {
        ExecutionEngine engine = engineWithNoSuchConnection();
        String executionId = engine.execute("SELECT 1", "deleted-connection");

        ExecutionResult result = null;
        for (int i = 0; i < 50; i++) {           // 最多 5s
            result = engine.getStatus(executionId);
            if (result != null && result.status().isTerminal()) break;
            Thread.sleep(100);
        }

        assertNotNull(result);
        assertEquals(ExecutionStatus.FAILED, result.status(),
            "run() 的 try 块之外抛出的异常也必须落终态，否则前端永远轮询、回收永远不命中");
        // 实测（recon 2026-10-02）：lookupDialect 对已删除的 connectionId 抛出
        // IllegalStateException("Connection not found while looking up dialect: deleted-connection")，
        // 兜底发布为 "Execution failed: " + 该 message。这里逐字钉住整条 error，
        // 而不是「含 id 或非空白」那种一支恒真的析取。
        assertEquals("Execution failed: Connection not found while looking up dialect: deleted-connection",
            result.error(),
            "兜底终态须带可观测原因（规则 #2），不能只 log 后照常返回；实际=" + result.error());
    }
}