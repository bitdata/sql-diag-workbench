package com.bitdata.sqlworkbench.optimization;

import com.bitdata.sqlworkbench.connection.ConnectionService;
import com.bitdata.sqlworkbench.connection.WorkloadLeaseManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.sql.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.bitdata.sqlworkbench.optimization.PerformanceExperimentContract.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Synthetic JDBC fault probes. These run the production task; they do not certify live databases. */
class PerformanceExperimentTasksLifecycleTest {
    static final String SQL="SELECT o.x FROM public.a AS o WHERE o.x>0";

    @Test
    void receiptCapacityExpiryAndTerminalDeleteNeverReplaySql() throws Exception {
        try(var fixture=new Fixture(3)) {
            String first=null;
            for(int i=0;i<16;i++){
                fixture.selects.set(0);
                Report report=fixture.run();if(first==null)first=report.experimentId();
                long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
                while(((Number)org.springframework.test.util.ReflectionTestUtils.getField(task(fixture.tasks,report.experimentId()),"terminalAt")).longValue()==0&&System.nanoTime()<end)Thread.sleep(1);
                assertEquals(report,fixture.tasks.cancel(report.experimentId()));
            }
            var source=new OptimizationSql.Source("session","revision",SQL,"connection","config","postgresql");
            var request=new Request(1,source,SQL,null,"42",30);
            assertEquals(429,assertThrows(OptimizationFailure.class,()->fixture.tasks.submit(request)).status);
            org.springframework.test.util.ReflectionTestUtils.setField(task(fixture.tasks,first),"terminalAt",System.currentTimeMillis()-300001);
            String expired=first;
            assertEquals(410,assertThrows(OptimizationFailure.class,()->fixture.tasks.get(expired)).status);
            assertEquals(404,assertThrows(OptimizationFailure.class,()->fixture.tasks.get("unknown")).status);
            fixture.selects.set(0);assertNotNull(fixture.run());
        }
    }

    @Test
    void generatedSeedUsesUnsigned32BitsEvenWhenRandomIntIsNegative()throws Exception {
        try(var fixture=new Fixture(3)){
            var source=new OptimizationSql.Source("session","revision",SQL,"connection","config","postgresql");
            var random=mock(java.util.concurrent.ThreadLocalRandom.class);
            when(random.nextInt()).thenReturn(-1);
            String id;
            try(var current=mockStatic(java.util.concurrent.ThreadLocalRandom.class)){
                current.when(java.util.concurrent.ThreadLocalRandom::current).thenReturn(random);
                id=fixture.tasks.submit(new Request(1,source,SQL,null,null,30)).experimentId();
            }
            assertEquals("4294967295",fixture.tasks.get(id).protocol().seed());
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void onlyNewest64ExpiredReceiptsKeepGoneStatus() throws Exception {
        try(var fixture=new Fixture(3)){
            var map=(java.util.Map<String,PerformanceExperimentTasks.Task>)org.springframework.test.util.ReflectionTestUtils.getField(fixture.tasks,"tasks");
            var source=new OptimizationSql.Source("session","revision",SQL,"connection","config","postgresql");
            String first=null,last=null;
            for(int n=0;n<65;n++){
                var lease=fixture.leases.acquireExclusive();
                var execution=fixture.tasks.new Task(new Request(1,source,SQL,null,"42",30),"42",lease);
                lease.release();execution.terminalAt=System.currentTimeMillis()-300001;
                synchronized(fixture.tasks){map.put(execution.id,execution);}
                String id=execution.id;
                assertEquals(410,assertThrows(OptimizationFailure.class,()->fixture.tasks.get(id)).status);
                if(first==null)first=id;last=id;
            }
            String oldest=first,newest=last;
            assertEquals(404,assertThrows(OptimizationFailure.class,()->fixture.tasks.get(oldest)).status);
            assertEquals(410,assertThrows(OptimizationFailure.class,()->fixture.tasks.get(newest)).status);
            assertEquals(0,fixture.selects.get());
        }
    }

    @Test
    void unconfirmedReceiptDoesNotExpireBeforeCleanupIsConfirmed() throws Exception {
        try(var fixture=new Fixture(3)) {
            Report report=fixture.run();
            var execution=(PerformanceExperimentTasks.Task)task(fixture.tasks,report.experimentId());
            execution.lease.markUnconfirmed("CONNECTION_CLEANUP_UNCONFIRMED");
            execution.terminalAt=System.currentTimeMillis()-300001;
            assertEquals(report,fixture.tasks.get(report.experimentId()));
            execution.lease.clearUnconfirmed();
            assertEquals(410,assertThrows(OptimizationFailure.class,()->fixture.tasks.get(report.experimentId())).status);
        }
    }

    @ParameterizedTest
    @ValueSource(strings={"ROW","CELL","BYTE"})
    void cumulativeBudgetRetainsTheFailedSampleAndDoesNotRetry(String budget) throws Exception {
        try(var fixture=new Fixture(0)) {
            var source=new OptimizationSql.Source("session","revision",SQL,"connection","config","postgresql");
            var lease=fixture.leases.acquireExclusive();
            var execution=fixture.tasks.new Task(new Request(1,source,SQL,null,"42",30),"42",lease);
            execution.cumulativeRows=budget.equals("ROW")?1_000_000:0;
            execution.cumulativeCells=budget.equals("CELL")?5_000_000:0;
            execution.cumulativeBytes=budget.equals("BYTE")?128L*1024*1024:0;
            var samples=new java.util.ArrayList<Sample>();
            try {
                var failure=assertThrows(PerformanceExperimentTasks.SampleFailure.class,()->fixture.tasks.appendSample(samples,fixture.connection,"postgresql",SQL,0,"WARMUP","AB",'A',execution));
                assertEquals("EXPERIMENT_"+budget+"_BUDGET_EXCEEDED",failure.sample.errorReason());
                assertEquals(1,samples.size());assertEquals(1,fixture.selects.get());
            }finally{lease.release();}
        }
    }

    @ParameterizedTest
    @ValueSource(strings={"rows","schema"})
    void timedReadDriftRetainsFailureAndStopsBeforeNextSelect(String drift) throws Exception {
        try(var fixture=new Fixture(0)) {
            fixture.drift=drift;Report report=fixture.run();
            assertEquals("FAILED",report.status());assertEquals("NOT_AVAILABLE",report.summary().state());
            assertEquals(drift.equals("rows")?"SAMPLE_ROW_COUNT_CHANGED":"SAMPLE_SCHEMA_CHANGED",report.errorReason());
            assertEquals(1,report.samples().size());assertEquals(3,fixture.selects.get());
            assertEquals("FAILED",report.samples().get(0).status());
        }
    }

    static Object task(PerformanceExperimentTasks tasks,String id){
        return ((java.util.Map<?,?>)org.springframework.test.util.ReflectionTestUtils.getField(tasks,"tasks")).get(id);
    }

    @ParameterizedTest
    @ValueSource(ints={3,4,7,8,25,26})
    void failedWarmupOrMeasuredSideIsPersistedAndNoLaterSelectRuns(int failAt) throws Exception {
        try(var fixture=new Fixture(failAt)) {
            Report report=fixture.run();
            assertEquals("FAILED",report.status());assertEquals("QUERY_FAILED",report.errorReason());
            assertEquals("NOT_AVAILABLE",report.summary().state());
            assertEquals(failAt-2,report.samples().size());
            Sample failed=report.samples().get(report.samples().size()-1);
            assertEquals("FAILED",failed.status());assertEquals("QUERY_FAILED",failed.errorReason());
            int position=failAt-3;boolean warmup=position<4;
            int pair=warmup?position/2:(position-4)/2;
            String order=PerformanceExperimentTasks.orders("42").get(position/2);
            assertEquals(warmup?"WARMUP":"MEASURED",failed.phase());assertEquals(pair,failed.pair());
            assertEquals(order,failed.order());assertEquals(String.valueOf(order.charAt(position%2)),failed.side());
            assertEquals(failAt,fixture.selects.get());
            verify(fixture.capture,never()).capture(any(),anyString(),anyString(),anyString(),anyString(),any());
            verify(fixture.store).save(report);verify(fixture.connection).close();
            assertFalse(fixture.leases.snapshot().exclusiveActive());
        }
    }

    @ParameterizedTest
    @ValueSource(strings={"CANCELLED","DEADLINE_EXCEEDED","ACTUAL_PLAN_CAPTURE_FAILED"})
    void planFailureOrStopRetainsMeasuredSamplesAndSeparatesTaskTerminalStatus(String reason) throws Exception {
        try(var fixture=new Fixture(0)) {
            when(fixture.capture.capture(any(),anyString(),anyString(),anyString(),anyString(),any())).thenAnswer(call->{
                ValidationControl control=call.getArgument(5);
                if(reason.equals("CANCELLED")) control.cancelled.set(true);
                if(reason.equals("DEADLINE_EXCEEDED")) {
                    org.springframework.test.util.ReflectionTestUtils.setField(control,"deadline",System.nanoTime()-1);
                }
                var evidence=new ActualPlanEvidence("FAILED","postgresql","POSTGRESQL_JSON","config",SQL,"EXPLAIN "+SQL,"2026-10-26T00:00:00Z",null,null,null,null,reason,true);
                return new ActualPlans(evidence,evidence,true,"ACTUAL_PLAN_EVIDENCE_INCOMPLETE");
            });
            Report report=fixture.run();
            assertEquals(reason.equals("CANCELLED")?"CANCELLED":reason.equals("DEADLINE_EXCEEDED")?"TIMED_OUT":"COMPLETED",report.status());
            assertEquals("COMPLETE",report.comparisonState());assertEquals("DESCRIPTIVE",report.summary().state());
            assertEquals(24,report.samples().size());assertTrue(report.samples().stream().allMatch(s->s.status().equals("COMPLETE")));
            assertEquals(26,fixture.selects.get());assertTrue(report.degraded());
            var execution=(PerformanceExperimentTasks.Task)task(fixture.tasks,report.experimentId());
            assertEquals(26*17,execution.cumulativeBytes);
            assertTrue(report.samples().stream().allMatch(sample->"17".equals(sample.bytes())));
            assertEquals(reason,report.actualPlans().original().errorReason());
            verify(fixture.store).save(report);
        }
    }

    static final class Fixture implements AutoCloseable {
        final Connection connection=mock(Connection.class);
        final PerformanceExperimentPlanCapture capture=mock(PerformanceExperimentPlanCapture.class);
        final PerformanceExperimentReportStore store=mock(PerformanceExperimentReportStore.class);
        final WorkloadLeaseManager leases=new WorkloadLeaseManager();
        final AtomicInteger selects=new AtomicInteger();
        String drift;
        final PerformanceExperimentTasks tasks;
        Fixture(int failAt) throws Exception {
            when(connection.isReadOnly()).thenReturn(true);when(connection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_REPEATABLE_READ);
            var pg=mock(org.postgresql.PGConnection.class);when(connection.unwrap(org.postgresql.PGConnection.class)).thenReturn(pg);
            var service=mock(ConnectionService.class);
            when(service.acquireExclusiveWorkload()).thenAnswer(i->leases.acquireExclusive());
            when(service.openJdbcConnection(anyString(),anyString(),any())).thenReturn(connection);
            var metadata=mock(DatabaseMetaData.class);
            when(metadata.getDatabaseProductName()).thenReturn("PostgreSQL");when(metadata.getDatabaseProductVersion()).thenReturn("16.14 synthetic");
            when(metadata.getDriverName()).thenReturn("synthetic");when(metadata.getDriverVersion()).thenReturn("1");when(metadata.getUserName()).thenReturn("fixture");
            when(connection.getMetaData()).thenReturn(metadata);when(connection.getCatalog()).thenReturn("fixture");
            when(connection.createStatement()).thenAnswer(i->{
                Statement statement=mock(Statement.class);
                when(statement.executeQuery(anyString())).thenAnswer(q->q.getArgument(0,String.class).contains("LIMIT 0")?rows(new Object[0][]):rows(new Object[][]{{"1"}}));
                return statement;
            });
            when(connection.prepareStatement(anyString())).thenAnswer(i->{
                PreparedStatement statement=mock(PreparedStatement.class);String sql=i.getArgument(0);
                when(statement.executeQuery()).thenAnswer(q->sql.contains("relkind")?rows(new Object[][]{{"r","p",false,false}}):sql.contains("typtype")?rows(new Object[][]{{"x","b","pg_catalog"}}):rows(new Object[0][]));
                return statement;
            });
            when(connection.prepareStatement(anyString(),anyInt(),anyInt())).thenAnswer(i->{
                PreparedStatement statement=mock(PreparedStatement.class);
                when(statement.executeQuery()).thenAnswer(q->{if(selects.incrementAndGet()==failAt)throw new SQLException("injected sample failure");if(selects.get()==3&&"rows".equals(drift))return rows(new Object[0][]);ResultSet result=rows(new Object[][]{{BigDecimal.ONE}});if(selects.get()==3&&"schema".equals(drift))when(result.getMetaData().getColumnLabel(1)).thenReturn("changed");return result;});
                return statement;
            });
            tasks=new PerformanceExperimentTasks(service,mock(OptimizationSources.class),store,new ObjectMapper(),capture);
        }
        Report run() throws Exception {
            var source=new OptimizationSql.Source("session","revision",SQL,"connection","config","postgresql");
            String id=tasks.submit(new Request(1,source,SQL,null,"42",30)).experimentId();
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            Report report;
            do {report=tasks.get(id);if(report.completedAt()!=null)return report;Thread.sleep(10);}while(System.nanoTime()<deadline);
            throw new AssertionError("task did not finish: "+report);
        }
        @Override public void close(){tasks.close();leases.close();}
    }

    static ResultSet rows(Object[][] data) throws Exception {
        ResultSet result=mock(ResultSet.class);ResultSetMetaData metadata=mock(ResultSetMetaData.class);
        when(metadata.getColumnCount()).thenReturn(1);when(metadata.getColumnLabel(1)).thenReturn("x");
        when(metadata.getColumnType(1)).thenReturn(Types.INTEGER);when(metadata.getColumnTypeName(1)).thenReturn("int4");
        when(metadata.getPrecision(1)).thenReturn(10);when(metadata.isSigned(1)).thenReturn(true);
        when(result.getMetaData()).thenReturn(metadata);
        AtomicInteger cursor=new AtomicInteger(-1);when(result.next()).thenAnswer(i->cursor.incrementAndGet()<data.length);
        when(result.getString(anyInt())).thenAnswer(i->String.valueOf(data[cursor.get()][i.getArgument(0,Integer.class)-1]));
        when(result.getBoolean(anyInt())).thenAnswer(i->data[cursor.get()][i.getArgument(0,Integer.class)-1]);
        when(result.getBigDecimal(1)).thenAnswer(i->data[cursor.get()][0]);
        return result;
    }
}
