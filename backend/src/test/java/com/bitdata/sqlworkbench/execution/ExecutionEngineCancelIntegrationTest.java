package com.bitdata.sqlworkbench.execution;

import com.bitdata.sqlworkbench.connection.ConnectionConfig;
import com.bitdata.sqlworkbench.connection.ConnectionRequest;
import com.bitdata.sqlworkbench.connection.ConnectionService;
import com.bitdata.sqlworkbench.connection.KeychainStore;
import com.bitdata.sqlworkbench.execution.model.ExecutionResult;
import com.bitdata.sqlworkbench.execution.model.ExecutionStatus;
import com.bitdata.sqlworkbench.testsupport.Dialect;
import com.bitdata.sqlworkbench.testsupport.RequiresTestDatabase;
import com.bitdata.sqlworkbench.testsupport.TestDatabaseResolver;
import com.bitdata.sqlworkbench.testsupport.TestDatabaseResolver.DatabaseEntry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * (B) 端到端：取消一个**正在跑**的执行，终态必须带原因（spec §4.3 (B) / §8）。
 *
 * <p>先到终态者胜（(C)）意味着赢家可能是 cancel() 入口站、某个 worker 检查点，
 * 或 worker 对取消信号的 SQLException 归类（TIMED_OUT）；因此这里只钉
 * 「已终态 + error 非空非空白」，**不**钉必然是 CANCELLED、也**不**钉具体措辞。
 * 旧实现四个站点全发 (CANCELLED, null)，getStatus 回落到 error==null 的终态 ⇒ 本用例红。
 *
 * <p>Task 5 收紧后 {@code cancel()} 的布尔只表示「本次调用发布的 CANCELLED 赢没赢
 * 终态闩锁」，不表示「取消生效了」，故本类不按它断言；二次取消的 false 是确定的
 * （第一次调用返回时终态必已发布），保留。
 */
@SpringBootTest
@RequiresTestDatabase(Dialect.POSTGRESQL)
@Tag("integration")
class ExecutionEngineCancelIntegrationTest {

    @MockBean private KeychainStore keychain;
    @Autowired private ExecutionEngine engine;
    @Autowired private ConnectionService connectionService;

    private String connectionId;

    @BeforeEach
    void setUp() {
        DatabaseEntry entry = TestDatabaseResolver.resolve(Dialect.POSTGRESQL);
        when(keychain.retrieve(anyString())).thenReturn(entry.password());
        ConnectionConfig config = connectionService.create(new ConnectionRequest(
            "test-pg-cancel-" + System.nanoTime(),
            entry.host(), entry.port(), entry.database(),
            entry.username(), entry.password(),
            "postgresql", entry.connectionType(), entry.trustServerCertificate()
        ));
        connectionId = config.id();
    }

    private ExecutionResult awaitStatus(String executionId, ExecutionStatus wanted, int attempts)
            throws InterruptedException {
        for (int i = 0; i < attempts; i++) {
            ExecutionResult r = engine.getStatus(executionId);
            if (r != null && r.status() == wanted) return r;
            Thread.sleep(100);
        }
        fail("execution " + executionId + " never reached " + wanted);
        return null;
    }

    private ExecutionResult awaitTerminal(String executionId, int attempts) throws InterruptedException {
        for (int i = 0; i < attempts; i++) {
            ExecutionResult r = engine.getStatus(executionId);
            if (r != null && r.status() != null && r.status().isTerminal()) return r;
            Thread.sleep(100);
        }
        fail("execution " + executionId + " never reached a terminal state");
        return null;
    }

    @Test
    void cancelledExecutionCarriesAReason() throws Exception {
        // pg_sleep(30) 保证取消打在「正在跑」而不是「还没排上」。
        String executionId = engine.execute("SELECT pg_sleep(30)", connectionId);
        awaitStatus(executionId, ExecutionStatus.RUNNING, 100);

        engine.cancel(executionId);

        // awaitTerminal(...) already fails if no terminal state is reached, so the
        // terminal-status invariant holds by construction here — asserting it again
        // would be an assertion that can never fail (worse than none).
        ExecutionResult result = awaitTerminal(executionId, 100);
        assertNotNull(result.error(),
            "(B): a terminal cancel outcome must carry a reason — old sites published "
                + "(CANCELLED, null), which getStatus surfaced as a null error");
        assertFalse(result.error().isBlank());
    }

    @Test
    void cancellingTwiceReportsTheSecondAsNoOp() throws Exception {
        String executionId = engine.execute("SELECT pg_sleep(30)", connectionId);
        awaitStatus(executionId, ExecutionStatus.RUNNING, 100);

        engine.cancel(executionId);
        awaitTerminal(executionId, 100);

        // (C)：第二次取消不得改写已发布的终态 —— 终态已占据闩锁，必然 false。
        assertFalse(engine.cancel(executionId));
        assertTrue(engine.getStatus(executionId).status().isTerminal());
    }
}
