package com.bitdata.sqlworkbench.execution;

import com.bitdata.sqlworkbench.connection.*;
import com.bitdata.sqlworkbench.execution.model.*;
import com.bitdata.sqlworkbench.history.*;
import com.bitdata.sqlworkbench.sql.SelectOnlyGuard;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.util.ReflectionTestUtils;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ExecutionCompletenessTest {
    ConnectionService service;
    ExecutionEngine engine;
    @BeforeEach void setup() throws Exception {
        service = mock(ConnectionService.class);
        when(service.getById("c")).thenReturn(Optional.of(new ConnectionConfig("c","test","",0,"","","postgresql","",false,"","",null,false)));
        when(service.openJdbcConnection("c")).thenAnswer(i -> DriverManager.getConnection("jdbc:sqlite::memory:"));
        var strategies = mock(DatabaseStrategyFactory.class);
        when(strategies.get("postgresql")).thenReturn(mock(DatabaseStrategy.class));
        engine = new ExecutionEngine(service, mock(ExplainServiceFactory.class), strategies, mock(SelectOnlyGuard.class),30,2);
    }
    @AfterEach void close() { engine.shutdown(); }
    ExecutionResult finish(String id) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        ExecutionResult result;
        do {
            result = engine.getStatus(id);
            if(result.status().isTerminal()) return result;
            Thread.sleep(2);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Execution did not finish");
    }
    @ParameterizedTest
    @CsvSource({"0,2,0,COMPLETE","1,2,1,COMPLETE","2,2,2,COMPLETE","3,2,2,TRUNCATED",
        "2,1,1,TRUNCATED","1,0,1,COMPLETE","3,-1,2,TRUNCATED","10001,50000,10000,TRUNCATED","10000,10000,10000,COMPLETE"})
    void capNeverBecomesTotal(int sourceRows, int limit, int returned, ResultCompleteness.State state) throws Exception {
        String sql = "WITH RECURSIVE nums(n) AS (SELECT 1 WHERE " + sourceRows + ">0 UNION ALL SELECT n+1 FROM nums WHERE n<" + sourceRows + ") SELECT n FROM nums";
        var result = finish(engine.execute(sql,"c",30,limit));
        assertEquals(ExecutionStatus.COMPLETED,result.status(),result.error());
        assertEquals(returned,result.rowCount()); assertEquals(returned,result.rows().size());
        assertEquals(state,result.resultCompleteness().state());
        assertNotNull(result.completedAt());
    }
    @Test void sqlLimitAndOffsetRemainPartOfTheResult() throws Exception {
        var result = finish(engine.execute("SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 LIMIT 2 OFFSET 1","c"));
        assertEquals(ResultCompleteness.State.COMPLETE,result.resultCompleteness().state());
        assertEquals(List.of(List.of(2),List.of(3)),result.rows());
    }
    @Test void historyAndCopiesKeepTheSameObservation() throws Exception {
        var history=mock(HistoryStore.class);
        ReflectionTestUtils.setField(engine,"historyStore",history);
        var result=finish(engine.execute("SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3","c",null,null,"owner"));
        var entry=org.mockito.ArgumentCaptor.forClass(HistoryEntry.class);
        verify(history).save(entry.capture());
        assertEquals(result.completedAt(),entry.getValue().executedAt());
        assertEquals(result.resultCompleteness(),entry.getValue().resultCompleteness());
        assertEquals(result.resultCompleteness(),result.withCredentialFailure("test").resultCompleteness());
        assertEquals(result.completedAt(),result.withHistory(null).completedAt());
    }
    @Test void failedHistoryDoesNotDiscardCompleteness() throws Exception {
        var history=mock(HistoryStore.class); doThrow(new IllegalStateException("offline")).when(history).save(any());
        ReflectionTestUtils.setField(engine,"historyStore",history);
        var result=finish(engine.execute("SELECT 1","c",null,null,"owner"));
        assertEquals(ExecutionStatus.COMPLETED,result.status());
        assertEquals("HISTORY_SAVE_FAILED",result.historyPersistence().errorReason());
        assertEquals(ResultCompleteness.State.COMPLETE,result.resultCompleteness().state());
    }
    @Test void invalidDefaultsFailBeforeAnyExecution() {
        for(int limit : new int[]{0,-1,10001}) assertThrows(IllegalArgumentException.class,() ->
            new ExecutionEngine(service,null,null,null,30,limit));
    }
    @Test void oldJsonIsUnknownAndSummaryRoundTrips() throws Exception {
        var json=new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
        var legacy=json.readValue("{\"rows\":2,\"durationMs\":4}",com.bitdata.sqlworkbench.sql.model.ExecutionSummary.class);
        assertEquals("COMPLETENESS_UNVERIFIED",legacy.resultCompleteness().errorReason());
        var summary=new com.bitdata.sqlworkbench.sql.model.ExecutionSummary(2,4,new ResultCompleteness(ResultCompleteness.State.TRUNCATED,2,null));
        assertEquals(summary,json.readValue(json.writeValueAsString(summary),summary.getClass()));
        var oldResult=json.readValue("{\"executionId\":\"old\",\"status\":\"COMPLETED\",\"rowCount\":0}",ExecutionResult.class);
        assertEquals(ResultCompleteness.State.UNKNOWN,oldResult.resultCompleteness().state());
        assertNull(oldResult.completedAt());
    }
}
