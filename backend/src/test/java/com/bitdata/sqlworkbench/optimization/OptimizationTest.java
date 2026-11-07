package com.bitdata.sqlworkbench.optimization;

import com.bitdata.sqlworkbench.connection.ConnectionService;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import static com.bitdata.sqlworkbench.optimization.OptimizationSql.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OptimizationTest {
    @Test void betweenPolicyRemainsExplicitlyUnsupportedRatherThanExpandingRewriteScope() {
        for(String dialect:List.of("mysql","postgresql")) {
            var source=new Source("s","r","SELECT o.x FROM public.a AS o WHERE o.x BETWEEN 1 AND 3","c","r",dialect);
            assertEquals("RULE_NOT_APPLICABLE",assertThrows(IllegalArgumentException.class,()->generate(source)).getMessage());
        }
    }
    Source source(String sql) {return new Source("session","revision",sql,"connection","config","postgresql");}
    String query(String condition) {return "SELECT o.x FROM public.a AS o WHERE "+condition;}
    @Test void realAstRewritesEachOccurrenceIndependentlyAndPreservesIdentity() throws Exception {
        String sql=query("o.x IN (SELECT i.x FROM public.b AS i) AND (SELECT COUNT(*) FROM public.b AS j WHERE j.x=o.x)>0");
        var g=generate(source(sql));assertEquals(2,g.candidates().size());assertEquals(sql,g.source().sql());
        assertNotEquals(g.candidates().get(0).id(),g.candidates().get(1).id());assertEquals(g,generate(source(sql)));
        for(var candidate:g.candidates()){assertTrue(candidate.sql().contains("EXISTS"));policy(candidate.sql(),"postgresql");}
        assertEquals(2,generate(source(query("o.x IN (SELECT i.x FROM public.b AS i) AND EXISTS(SELECT 1 FROM public.b AS j WHERE j.x=o.x)"))).skipped().size()+1);
    }
    @Test void exhaustiveUnsupportedShapesNeverEmitCandidate() {
        List<String> cases=List.of(
            query("o.x NOT IN (SELECT i.x FROM public.b AS i)"),query("o.x IN (SELECT i.x FROM public.b AS i) OR o.x=1"),
            query("(SELECT COUNT(i.x) FROM public.b AS i)>0"),query("(SELECT COUNT(i.*) FROM public.b AS i)>0"),
            query("(SELECT COUNT(DISTINCT i.x) FROM public.b AS i)>0"),query("(SELECT public.count(*) FROM public.b AS i)>0"),
            query("(SELECT \"count\"(*) FROM public.b AS i)>0"),query("(SELECT COUNT(*) FROM public.b AS i GROUP BY i.x)>0"),
            query("(SELECT COUNT(*) FROM public.b AS i LIMIT 0)>0"),query("(SELECT COUNT(*) FROM public.b AS i)>=0"),
            query("o.x IN (SELECT i.x FROM public.b AS i ORDER BY i.x)"),query("o.x IN (SELECT o.x FROM public.b AS o)"),
            query("x IN (SELECT i.x FROM public.b AS i)"),query("o.x IN (SELECT i.x FROM public.b AS i WHERE random()>0)"),
            "SELECT o.x IN (SELECT i.x FROM public.b AS i) FROM public.a AS o", "SELECT * FROM public.a AS o", "SELECT o.x FROM public.a AS o JOIN public.b AS i ON i.x=o.x",
            "WITH q AS (SELECT 1) SELECT o.x FROM public.a AS o",query("o.x=1")+"; DELETE FROM public.a",
            query("o.x IN (SELECT i.x FROM public.b AS i)")+" FOR UPDATE",
            query("o.x=1")+" /*! AND SLEEP(1)=0 */",query("o.x=1")+" /*+ hint */");
        for(String sql:cases) {try{assertTrue(generate(source(sql)).candidates().isEmpty(),sql);}catch(IllegalArgumentException expected){assertNotNull(expected.getMessage());}catch(Exception e){fail(sql,e);}}
    }
    @Test void budgetsAndPostgresCasingAreIndependentOfLegacyParser() throws Exception {
        assertEquals("o",reference(select(parse("SELECT O.X FROM PUBLIC.A AS O","postgresql")).getFrom()).alias());
        assertEquals("O",reference(select(parse("SELECT O.X FROM PUBLIC.A AS O","mysql")).getFrom()).alias());
        assertEquals("SQL_BUDGET_EXCEEDED",assertThrows(IllegalArgumentException.class,()->parse("x".repeat(32769),"postgresql")).getMessage());
        assertEquals("AST_BUDGET_EXCEEDED",assertThrows(IllegalArgumentException.class,()->parse(query(String.join(" AND ",Collections.nCopies(100,"o.x=1"))),"postgresql")).getMessage());
        assertEquals("CANDIDATE_BUDGET_EXCEEDED",assertThrows(IllegalArgumentException.class,()->policy(query(String.join(" AND ",Collections.nCopies(17,"o.x=1"))),"postgresql")).getMessage());
    }
    @Test void typedValuesKeepPrecisionNullFramingAndSchema() throws Exception {
        assertNotEquals(List.of(new Cell("TEXT","a|b"),new Cell("TEXT","c")),List.of(new Cell("TEXT","a"),new Cell("TEXT","b|c")));
        assertNotEquals(new Cell("NULL",null),new Cell("TEXT",""));assertNotEquals(new Cell("TEXT","é"),new Cell("TEXT","e\u0301"));
        var r=mock(ResultSet.class);when(r.getBigDecimal(1)).thenReturn(new java.math.BigDecimal("9007199254740993.000000000000000001"));
        assertEquals("9007199254740993.000000000000000001",ExactResultBag.cell(r,1,Types.DECIMAL,new ValidationControl(30)).value());
        when(r.getCharacterStream(1)).thenReturn(new java.io.StringReader("中".repeat(22000)));
        assertEquals("CELL_BUDGET_EXCEEDED",assertThrows(IllegalArgumentException.class,()->ExactResultBag.cell(r,1,Types.VARCHAR,new ValidationControl(30))).getMessage());
        assertThrows(IllegalArgumentException.class,()->ExactResultBag.family(Types.FLOAT));
    }
    @Test void reportAlwaysEmitsExplicitNullUnderApplicationNonNullPolicy() throws Exception {
        var report=new Report(1,"id","QUEUED","NOT_EVALUATED",null,false,source(query("o.x=1")),query("o.x=2"),null,null,"EXACT_TYPED_BAG_V1",null,null,null,List.of(),false,null,null,new Persistence("PENDING",null));
        var json=new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);var tree=json.readTree(json.writeValueAsString(report));
        for(String key:List.of("errorReason","snapshot","candidateId","candidateEvidence","original","candidate","startedAt","completedAt"))assertTrue(tree.has(key),key);
        assertEquals(report,json.readValue(json.writeValueAsString(report),Report.class));
    }
    @Test void handshakeDeadlineAndQueueCancellationRetainWorkerUntilLateConnectionCloses() throws Exception {
        var connections=mock(ConnectionService.class);var sources=mock(OptimizationSources.class);var store=mock(OptimizationReportStore.class);
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var connection=mock(Connection.class);
        when(connection.isClosed()).thenReturn(true);
        when(connections.openJdbcConnection(anyString(),anyString())).thenAnswer(i->{entered.countDown();release.await();return connection;});
        var tasks=new OptimizationTasks(connections,sources,store,new ObjectMapper());
        try {
            var request=new ValidationRequest(source(query("o.x=1")),query("o.x=2"),null,1);
            String first=tasks.submit(request).validationId();assertTrue(entered.await(3,TimeUnit.SECONDS));
            String queued=tasks.submit(request).validationId();tasks.cancel(queued);assertEquals("CANCELLED",tasks.get(queued).status());
            String otherQueued=tasks.submit(request).validationId(),lastQueued=tasks.submit(request).validationId();assertEquals(429,assertThrows(OptimizationFailure.class,()->tasks.submit(request)).status);
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(!"DEADLINE_CLEANUP_PENDING".equals(tasks.get(first).errorReason())&&System.nanoTime()<end)Thread.sleep(10);
            assertEquals("RUNNING",tasks.get(first).status());assertEquals("INCONCLUSIVE",tasks.get(first).verdict());verify(connections,times(1)).openJdbcConnection(anyString(),anyString());
            tasks.cancel(otherQueued);tasks.cancel(lastQueued);
            release.countDown();end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(!"TIMED_OUT".equals(tasks.get(first).status())&&System.nanoTime()<end)Thread.sleep(10);
            assertEquals("TIMED_OUT",tasks.get(first).status());verify(connection).close();verify(connection,never()).createStatement();
        }finally{release.countDown();tasks.close();}
    }
    @Test void retainedReceiptsReserveCapacityAndExpiredReceiptsAreDistinctFromUnknown() throws Exception {
        var connections=mock(ConnectionService.class);var sources=mock(OptimizationSources.class);var store=mock(OptimizationReportStore.class);
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        when(connections.openJdbcConnection(anyString(),anyString())).thenAnswer(i->{entered.countDown();release.await();throw new SQLException("controlled");});
        var tasks=new OptimizationTasks(connections,sources,store,new ObjectMapper());
        try {
            var request=new ValidationRequest(source(query("o.x=1")),query("o.x=2"),null,30);
            String running=tasks.submit(request).validationId();assertTrue(entered.await(3,TimeUnit.SECONDS));String receipt=null;
            for(int i=0;i<15;i++){receipt=tasks.submit(request).validationId();assertEquals("CANCELLED",tasks.cancel(receipt).status());}
            assertEquals(429,assertThrows(OptimizationFailure.class,()->tasks.submit(request)).status);
            var inventory=(Map<?,?>)org.springframework.test.util.ReflectionTestUtils.getField(tasks,"tasks");
            org.springframework.test.util.ReflectionTestUtils.setField(inventory.get(receipt),"terminalAt",System.currentTimeMillis()-300001);
            String expired=receipt;assertEquals(410,assertThrows(OptimizationFailure.class,()->tasks.get(expired)).status);
            assertEquals(404,assertThrows(OptimizationFailure.class,()->tasks.get("unknown")).status);
            assertEquals("RUNNING",tasks.get(running).status());
            assertNotNull(tasks.submit(request));
        }finally{release.countDown();tasks.close();}
    }
    @Test void controllerBoundsContentLengthAndUnknownLengthBeforeDecoding() {
        var controller=new OptimizationController(null,null,null,new ObjectMapper());
        for(boolean known:List.of(true,false)) {
            var request=new org.springframework.mock.web.MockHttpServletRequest() {
                @Override public long getContentLengthLong(){return known?128*1024+1:-1;}
            };
            request.setContent(new byte[128*1024+1]);
            assertEquals("REQUEST_BUDGET_EXCEEDED",assertThrows(OptimizationFailure.class,()->controller.candidates(request)).reason);
        }
    }
    @Test void permissionErrorsDoNotMislabelEverySqlState42Failure() {
        assertTrue(OptimizationTasks.permissionDenied(new SQLException("denied","42501")));
        assertTrue(OptimizationTasks.permissionDenied(new SQLException("denied","42000",1142)));
        assertFalse(OptimizationTasks.permissionDenied(new SQLException("missing table","42P01")));
        assertFalse(OptimizationTasks.permissionDenied(new SQLException("syntax","42000",1064)));
    }
    @Test void reportSaveFailureRemainsSeparateFromCancellationAndDoesNotExecuteQueuedSql() throws Exception {
        var connections=mock(ConnectionService.class);var sources=mock(OptimizationSources.class);var store=mock(OptimizationReportStore.class);
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        when(connections.openJdbcConnection(anyString(),anyString())).thenAnswer(i->{entered.countDown();release.await();throw new SQLException("controlled");});
        doThrow(new SQLException("storage unavailable")).when(store).save(any());
        var tasks=new OptimizationTasks(connections,sources,store,new ObjectMapper());
        try {
            var request=new ValidationRequest(source(query("o.x=1")),query("o.x=2"),null,30);
            tasks.submit(request);assertTrue(entered.await(3,TimeUnit.SECONDS));String queued=tasks.submit(request).validationId();
            var report=tasks.cancel(queued);assertEquals("CANCELLED",report.status());assertEquals("INCONCLUSIVE",report.verdict());
            assertEquals("FAILED",report.persistence().status());assertEquals("REPORT_SAVE_FAILED",report.persistence().errorReason());
            verify(connections,times(1)).openJdbcConnection(anyString(),anyString());
        }finally{release.countDown();tasks.close();}
    }
}
