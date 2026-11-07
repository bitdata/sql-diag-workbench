package com.bitdata.sqlworkbench.optimization;

import com.bitdata.sqlworkbench.connection.ConnectionService;
import com.bitdata.sqlworkbench.connection.WorkloadLeaseManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

import static com.bitdata.sqlworkbench.optimization.OptimizationSql.Source;
import static com.bitdata.sqlworkbench.optimization.PerformanceExperimentContract.Request;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PerformanceExperimentTasksSampleTest {
    @Test
    void pairedMedianUsesPairedDifferencesAndRejectsDuplicatePairs() {
        var tasks=new PerformanceExperimentTasks(mock(ConnectionService.class),mock(OptimizationSources.class),mock(PerformanceExperimentReportStore.class),new ObjectMapper(),null);
        try {
            var samples=new ArrayList<PerformanceExperimentContract.Sample>();
            for(int pair=0;pair<10;pair++) {
                long a=pair+1,b=pair<4?pair+1:100;
                samples.add(new PerformanceExperimentContract.Sample(pair,"MEASURED","AB","A","COMPLETE",Long.toString(a),"0","0","0","1","17",null));
                samples.add(new PerformanceExperimentContract.Sample(pair,"MEASURED","AB","B","COMPLETE",Long.toString(b),"0","0","0","1","17",null));
            }
            var summary=tasks.summarize(samples,true);
            assertEquals("-90.5",summary.pairedMedianDeltaNs());
            assertNotEquals("-94.5",summary.pairedMedianDeltaNs());
            samples.set(0,samples.get(2));
            assertEquals("NOT_AVAILABLE",tasks.summarize(samples,true).state());
        } finally {tasks.close();}
    }

    @Test
    void queryFailureIsRetainedWithPairOrderSideAndReasonAndIsNotRetried() throws Exception {
        var connectionService=mock(ConnectionService.class);
        var tasks=new PerformanceExperimentTasks(connectionService,mock(OptimizationSources.class),
            mock(PerformanceExperimentReportStore.class),new ObjectMapper(),null);
        var leases=new WorkloadLeaseManager();var lease=leases.acquireExclusive();
        try {
            Request request=new Request(1,new Source("session","source-rev","SELECT 1","connection","connection-rev","postgresql"),"SELECT 2",null,"42",30);
            var task=tasks.new Task(request,"42",lease);
            Connection connection=mock(Connection.class);PreparedStatement statement=mock(PreparedStatement.class);
            when(connection.prepareStatement("SELECT 2",ResultSet.TYPE_FORWARD_ONLY,ResultSet.CONCUR_READ_ONLY)).thenReturn(statement);
            when(statement.executeQuery()).thenThrow(new SQLException("injected sample failure"));
            List<PerformanceExperimentContract.Sample> samples=new ArrayList<>();

            var failure=assertThrows(PerformanceExperimentTasks.SampleFailure.class,
                ()->tasks.appendSample(samples,connection,"postgresql","SELECT 2",7,"MEASURED","BA",'B',task));

            assertEquals(1,samples.size());assertSame(failure.sample,samples.get(0));
            assertEquals(7,failure.sample.pair());assertEquals("MEASURED",failure.sample.phase());
            assertEquals("BA",failure.sample.order());assertEquals("B",failure.sample.side());
            assertEquals("FAILED",failure.sample.status());assertEquals("QUERY_FAILED",failure.sample.errorReason());
            assertNotNull(failure.sample.durationNs());
            verify(connection,times(1)).prepareStatement("SELECT 2",ResultSet.TYPE_FORWARD_ONLY,ResultSet.CONCUR_READ_ONLY);
            verify(statement,times(1)).executeQuery();
        } finally { lease.release();leases.close();tasks.close(); }
    }

    @Test
    void cancellationIsRetainedAsTheCurrentSampleAndStopsFurtherWork() throws Exception {
        var tasks=new PerformanceExperimentTasks(mock(ConnectionService.class),mock(OptimizationSources.class),
            mock(PerformanceExperimentReportStore.class),new ObjectMapper(),null);
        var leases=new WorkloadLeaseManager();var lease=leases.acquireExclusive();
        try {
            Request request=new Request(1,new Source("session","source-rev","SELECT 1","connection","connection-rev","postgresql"),"SELECT 2",null,"42",30);
            var task=tasks.new Task(request,"42",lease);task.control.cancelled.set(true);
            Connection connection=mock(Connection.class);PreparedStatement statement=mock(PreparedStatement.class);
            when(connection.prepareStatement("SELECT 1",ResultSet.TYPE_FORWARD_ONLY,ResultSet.CONCUR_READ_ONLY)).thenReturn(statement);
            List<PerformanceExperimentContract.Sample> samples=new ArrayList<>();

            assertThrows(PerformanceExperimentTasks.SampleFailure.class,
                ()->tasks.appendSample(samples,connection,"postgresql","SELECT 1",2,"WARMUP","AB",'A',task));

            assertEquals(1,samples.size());assertEquals("CANCELLED",samples.get(0).status());
            assertEquals("CANCELLED",samples.get(0).errorReason());
            verify(statement,never()).executeQuery();
        } finally { lease.release();leases.close();tasks.close(); }
    }
}
