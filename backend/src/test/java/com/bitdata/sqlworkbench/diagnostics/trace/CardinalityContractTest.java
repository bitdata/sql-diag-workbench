package com.bitdata.sqlworkbench.diagnostics.trace;

import com.bitdata.sqlworkbench.connection.*;
import com.bitdata.sqlworkbench.diagnostics.RuleRegistry;
import com.bitdata.sqlworkbench.sql.*;
import com.bitdata.sqlworkbench.sql.model.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CardinalityContractTest {
    TraceStore store; RowTraceEngine engine; ConnectionService service;
    SqlDialectResolver resolver=com.bitdata.sqlworkbench.sql.decompose.RuleTestDeps.testResolver();
    @BeforeEach void setup() throws Exception {
        store=new TraceStore(); service=mock(ConnectionService.class);
        when(service.getById("c")).thenReturn(Optional.of(new ConnectionConfig("c","test","",0,"","","postgresql","",false,"","",null,false)));
        when(service.openJdbcConnection("c")).thenAnswer(i -> DriverManager.getConnection("jdbc:sqlite::memory:"));
        var factory=mock(DatabaseStrategyFactory.class); var strategy=new PostgreSqlStrategy(); when(factory.get("postgresql")).thenReturn(strategy);
        // SQLite supplies real rows/metadata/binding here, while avoiding PostgreSQL connection configuration.
        var adapter=mock(DatabaseStrategy.class); when(adapter.getDialectProfile()).thenReturn(strategy.getDialectProfile());
        when(factory.get("postgresql")).thenReturn(adapter);
        engine=new RowTraceEngine(service,factory,store,mock(RuleRegistry.class),new SelectOnlyGuard(resolver),new SqlParseEngine(resolver),5);
    }
    @AfterEach void close() { engine.shutdown(); store.shutdown(); }
    FragmentNode node(String id,String sql,String rule,FragmentNode... children) {
        return new FragmentNode(id,FragmentType.ORIGINAL,id,sql,true,null,children.length,rule,List.of(children));
    }
    TraceProgress run(FragmentNode root,List<KeyValue> keys,String maximum) throws Exception {
        var request=new TraceRequest("c",new ObjectMapper().writeValueAsString(root),keys,5,TraceMode.CARDINALITY,maximum);
        String id=store.create(request); engine.executeTraceSync(id,request); return store.toProgress(id);
    }
    @Test void repeatedUnionBranchesKeepPositionsAndReuseOneMeasurement() throws Exception {
        var child=node("branch","SELECT 1 AS id","set-op-split");
        var root=node("root","SELECT 1 AS id UNION ALL SELECT 1 AS id",null,child,child);
        var result=run(root,List.of(new KeyValue("id",1)),"1");
        assertEquals(TraceStatus.COMPLETED,result.status(),result.error());
        assertEquals("2",result.quantity().rootCount()); assertEquals("EXCEEDS_EXPECTATION",result.quantity().verdict());
        assertEquals(2,result.steps().size()); assertEquals("root",result.steps().get(0).fragmentId());
        assertTrue(result.quantity().comparisons().isEmpty(),"Legacy edges retain counts but do not grant comparisons");
        assertTrue(result.evidence().limitations().stream().anyMatch(l->l.code().equals("LEGACY_TRANSFORM_UNKNOWN")));
        assertTrue(result.steps().stream().allMatch(s -> s.rowCount()==-1 && s.fragmentSql()!=null && s.observedAt()!=null));
    }
    @Test void allKeysAndExplicitNullDetermineTheCount() throws Exception {
        var root=node("root","SELECT '001' AS code, NULL AS tag UNION ALL SELECT '1', NULL UNION ALL SELECT '001','x'",null);
        var result=run(root,List.of(new KeyValue("code","001",KeyValue.Type.TEXT),new KeyValue("tag",null,KeyValue.Type.NULL)),"1");
        assertEquals("1",result.quantity().rootCount()); assertEquals("WITHIN_EXPECTATION",result.quantity().verdict());
        var missing=run(root,List.of(new KeyValue("code","001"),new KeyValue("absent",null)),"1");
        assertEquals("UNKNOWN",missing.quantity().verdict()); assertNull(missing.quantity().rootCount());
        assertEquals("KEY_NOT_PROJECTED",missing.steps().get(0).reasonCode());
    }
    @Test void conflictingIdentityAndOversizedScopeDoNotOpenDatabase() throws Exception {
        var a=node("same","SELECT 1 AS id","set-op-split"); var b=node("same","SELECT 2 AS id","set-op-split");
        var result=run(node("root","SELECT 1 AS id",null,a,b),List.of(new KeyValue("id",1)),null);
        assertEquals("TRACE_NODE_CONFLICT",result.errorReason()); assertEquals("UNKNOWN",result.quantity().verdict());
        verify(service,never()).openJdbcConnection(anyString());
        var children=new FragmentNode[65]; for(int i=0;i<children.length;i++) children[i]=node("child"+i,"SELECT 1 AS id",null);
        assertEquals("TRACE_SCOPE_TOO_LARGE",run(node("root","SELECT 1 AS id",null,children),List.of(new KeyValue("id",1)),null).errorReason());
    }
    @Test void joinDoesNotCompareAgainstAColumnFromTheOtherSource() {
        var orders=node("orders","SELECT o.id FROM (SELECT 1 AS id UNION ALL SELECT 1 AS id) o","join-split");
        var tags=node("tags","SELECT t.id FROM (SELECT 1 AS id) t","join-split");
        var root=node("root","SELECT o.id FROM (SELECT 1 AS id UNION ALL SELECT 1 AS id) o JOIN (SELECT 1 AS id) t ON o.id=t.id",null,orders,tags);
        var scope=new TraceInputValidator().validate(root);
        List<TraceStep> steps=List.of(step("root","2"),step("orders","2"),step("tags","1"));
        var authorization=new TransformVerifier.Verified(Set.of(),Set.of(
            new TransformVerifier.Position("root","orders","join-split",1),
            new TransformVerifier.Position("root","tags","join-split",2)),List.of());
        var result=new CardinalityStructureInspector(resolver).inspect(scope,steps,List.of(new KeyValue("id",1)),"postgresql",authorization);
        assertTrue(result.comparisons().isEmpty());
        result=new CardinalityStructureInspector(resolver).inspect(scope,List.of(step("root","3"),step("orders","2"),step("tags","1")),List.of(new KeyValue("id",1)),"postgresql",authorization);
        assertEquals(1,result.comparisons().size()); assertEquals("orders",result.comparisons().get(0).observations().get(0).fragmentId());
    }
    TraceStep step(String id,String count) { return new TraceStep(0,id,id,"",TraceStatus.HAS_ROWS,-1,0,null).withObservation("",count); }
    @Test void typedDecimalNeverPassesThroughDouble() throws Exception {
        var stmt=mock(PreparedStatement.class); var key=new KeyValue("id","9007199254740993",KeyValue.Type.DECIMAL);
        new TraceQueryBuilder().bindParameters(stmt,List.of(key));
        verify(stmt).setBigDecimal(1,new java.math.BigDecimal("9007199254740993")); verify(stmt,never()).setDouble(anyInt(),anyDouble());
        assertThrows(IllegalArgumentException.class,() -> new KeyValue("id",123,KeyValue.Type.DECIMAL));
        assertThrows(IllegalArgumentException.class,() -> new KeyValue("id",Map.of()));
        assertThrows(IllegalArgumentException.class,() -> new TraceRequest("c","{}",List.of(key),5,TraceMode.EXISTENCE,"1"));
    }
    @Test void queuedBudgetExpiresWithoutDispatchAndTerminalCountsStayUnknown() {
        var clock=new java.util.concurrent.atomic.AtomicLong(); var timed=new TraceStore(clock::get);
        try {
            var request=new TraceRequest("c","{}",List.of(new KeyValue("id",1)),5,TraceMode.CARDINALITY,"1");
            String id=timed.create(request); clock.set(TimeUnit.SECONDS.toNanos(119)+1);
            assertEquals(1,timed.remainingTimeout(id,30));
            clock.set(TimeUnit.SECONDS.toNanos(120)); timed.remainingTimeout(id,30);
            assertFalse(timed.canExecute(id)); assertEquals("TRACE_BUDGET_EXCEEDED",timed.toProgress(id).errorReason());
            assertEquals("UNKNOWN",timed.toProgress(id).quantity().verdict()); assertTrue(timed.toProgress(id).steps().isEmpty());
            timed.setRunning(id); assertEquals(TraceStatus.FAILED,timed.toProgress(id).status());
        } finally { timed.shutdown(); }
    }
    @Test void blockedBudgetCancellationDoesNotDelayAnotherExpiry() throws Exception {
        var request=new TraceRequest("c","{}",List.of(new KeyValue("id",1)),5,TraceMode.CARDINALITY,"1");
        String first=store.create(request), second=store.create(request);
        var entered=new CountDownLatch(1); var release=new CountDownLatch(1);
        Statement stmt=mock(Statement.class);
        doAnswer(i -> { entered.countDown(); release.await(5,TimeUnit.SECONDS); return null; }).when(stmt).cancel();
        store.registerActiveStatement(first,stmt);
        try {
            store.expireBudget(first); assertTrue(entered.await(2,TimeUnit.SECONDS));
            store.expireBudget(second);
            assertEquals("TRACE_BUDGET_EXCEEDED",store.toProgress(second).errorReason());
            assertEquals(TraceStatus.FAILED,store.toProgress(first).status());
            assertEquals("PENDING",store.toProgress(first).cancelSignal().state());
            assertFalse(store.canExecute(second));
        } finally { release.countDown(); }
    }
    @Test void countScalarRequiresAnExactNonnegativeSignedLong() throws Exception {
        for (String scalar : new String[]{"9223372036854775807","9223372036854775808","-1","1.5","NULL"}) {
            Connection conn=mock(Connection.class); PreparedStatement metadata=mock(PreparedStatement.class), count=mock(PreparedStatement.class);
            ResultSet projected=mock(ResultSet.class), counted=mock(ResultSet.class); ResultSetMetaData labels=mock(ResultSetMetaData.class);
            when(labels.getColumnCount()).thenReturn(1); when(labels.getColumnLabel(1)).thenReturn("id");
            when(conn.prepareStatement(anyString())).thenAnswer(i -> ((String)i.getArgument(0)).startsWith("SELECT COUNT(*)") ? count : metadata);
            when(metadata.executeQuery()).thenReturn(projected); when(projected.getMetaData()).thenReturn(labels);
            when(count.executeQuery()).thenReturn(counted); when(counted.getMetaData()).thenReturn(labels);
            when(counted.next()).thenReturn(true,false);
            when(counted.getBigDecimal(1)).thenReturn(scalar.equals("NULL")?null:new java.math.BigDecimal(scalar));
            when(service.openJdbcConnection("c")).thenReturn(conn);
            var result=run(node("root","SELECT 1 AS id",null),List.of(new KeyValue("id",1)),"1");
            if(scalar.equals("9223372036854775807")) {
                assertEquals(scalar,result.quantity().rootCount()); assertEquals("EXCEEDS_EXPECTATION",result.quantity().verdict());
            } else {
                assertNull(result.quantity().rootCount(),scalar); assertEquals("UNKNOWN",result.quantity().verdict());
                assertEquals("COUNT_RESULT_INVALID",result.steps().get(0).reasonCode());
            }
        }
    }
    @Test void expectedMaximumRequiresAJsonString() throws Exception {
        var json=new ObjectMapper();
        assertThrows(com.fasterxml.jackson.core.JsonProcessingException.class,() -> json.readValue(
            "{\"connectionId\":\"c\",\"fragmentTreeJson\":\"{}\",\"keys\":[{\"column\":\"id\",\"value\":1}],\"mode\":\"CARDINALITY\",\"expectedMax\":1}",TraceRequest.class));
    }
    @Test void blockingCancelDoesNotBlockGetOrMutateFrozenObservations() throws Exception {
        var request=new TraceRequest("c","{}",List.of(new KeyValue("id",1)),5,TraceMode.CARDINALITY,"1");
        String id=store.create(request); store.configureScope(id,"root",1); store.addStep(id,step("root","2"));
        Statement stmt=mock(Statement.class); CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        doAnswer(i -> {entered.countDown();release.await(5,TimeUnit.SECONDS);throw new SQLException("cancel failed");}).when(stmt).cancel();
        store.registerActiveStatement(id,stmt);
        var pool=Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> cancelled=pool.submit(() -> store.cancel(id)); assertTrue(entered.await(2,TimeUnit.SECONDS));
            var frozen=store.toProgress(id); assertEquals(TraceStatus.CANCELLED,frozen.status());
            assertEquals("PENDING",frozen.cancelSignal().state()); assertEquals("UNKNOWN",frozen.quantity().verdict());
            store.addStep(id,step("late","3")); assertFalse(store.completeQuantity(id,List.of(),List.of()));
            release.countDown(); assertTrue(cancelled.get(2,TimeUnit.SECONDS));
            assertEquals(frozen.steps(),store.toProgress(id).steps()); assertEquals("FAILED",store.toProgress(id).cancelSignal().state());
        } finally { release.countDown();pool.shutdownNow(); }
    }
}
