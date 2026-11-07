package com.bitdata.sqlworkbench.connection;

import com.bitdata.sqlworkbench.diagnostics.FindingVerifier;
import com.bitdata.sqlworkbench.diagnostics.source.*;
import com.bitdata.sqlworkbench.diagnostics.trace.*;
import com.bitdata.sqlworkbench.execution.*;
import com.bitdata.sqlworkbench.optimization.*;
import com.bitdata.sqlworkbench.session.*;
import com.bitdata.sqlworkbench.sql.*;
import com.bitdata.sqlworkbench.sql.model.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.calcite.sql.parser.SqlParser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Uses production Spring services and the real shared lease. No database should be contacted. */
@SpringBootTest(properties={"sql-workbench.data-dir=${java.io.tmpdir}/perf04-admission-${random.uuid}"})
class WorkloadAdmissionTest {
    @Autowired ConnectionService connections;
    @Autowired WorkloadLeaseManager leases;
    @Autowired ExecutionEngine execution;
    @Autowired RowTraceEngine traces;
    @Autowired TraceStore traceStore;
    @Autowired FindingVerifier verifier;
    @Autowired PhysicalSourcePlanService physical;
    @Autowired ComplexSourcePlanService complex;
    @Autowired PlanCapabilityService capabilities;
    @Autowired ConnectedColumnResolver columns;
    @Autowired OptimizationSources sources;
    @Autowired OptimizationTasks validations;
    @Autowired PerformanceExperimentTasks experiments;
    @Autowired SessionStore sessions;
    @Autowired ObjectMapper json;
    @MockBean KeychainStore keychain;

    @Test
    void allOrdinaryEntrypointsObserveExperimentExclusionBeforeCredentialRead()throws Exception {
        var config=connections.create(new ConnectionRequest("admission-fixture","127.0.0.1",1,"synthetic","synthetic","synthetic","postgresql",null,null));
        String sql="SELECT e.emp_no FROM public.employees AS e WHERE e.emp_no>0";
        String session="admission-session";
        sessions.save(Session.builder().id(session).name(session).sqlText(sql).dialect("postgresql").build());
        var source=sources.capture(new OptimizationSql.CandidateRequest(session,"revision",sql,config.id(),"postgresql"));
        var keys=List.of(new KeyValue("emp_no","10001",KeyValue.Type.DECIMAL));
        var request=new PhysicalSourcePlanService.Request(config.id(),sql,keys);
        var root=new FragmentNode("root",FragmentType.ORIGINAL,"Synthetic root",sql,true,null,0,null,List.of());
        clearInvocations(keychain);
        var ticket=leases.acquireExclusive();
        try {
            assertEquals("WORKLOAD_BUSY",assertThrows(WorkloadBusyException.class,()->connections.openJdbcConnection(config.id())).errorReason());
            assertEquals("WORKLOAD_BUSY",connections.test(new ConnectionRequest("test","127.0.0.1",1,"synthetic","synthetic","synthetic","postgresql",null,null)).get("errorReason"));
            assertEquals("WORKLOAD_BUSY",capabilities.get(config.id()).errorReason());
            assertEquals("WORKLOAD_BUSY",verifier.verify(sql,config.id()).errorReason());
            assertEquals("WORKLOAD_BUSY",physical.inspect(request).errorReason());
            assertEquals("WORKLOAD_BUSY",complex.inspect(request).errorReason());
            assertTrue(columns.resolve(SqlParser.create(sql).parseQuery(),"postgresql",config.id()).issues().stream().anyMatch(issue->"WORKLOAD_BUSY".equals(issue.code())));
            for(String id:List.of(execution.execute(sql,config.id()),execution.analyze(sql,config.id()))){
                await(()->execution.getStatus(id).errorReason()!=null);
                assertEquals("WORKLOAD_BUSY",execution.getStatus(id).errorReason());
            }
            for(var mode:List.of(TraceMode.EXISTENCE,TraceMode.CARDINALITY)){
                String id=traces.submit(new TraceRequest(config.id(),json.writeValueAsString(root),keys,10,mode,null));
                await(()->traceStore.toProgress(id).errorReason()!=null);
                assertEquals("WORKLOAD_BUSY",traceStore.toProgress(id).errorReason());
            }
            String validation=validations.submit(new OptimizationSql.ValidationRequest(source,sql,null,10)).validationId();
            await(()->validations.get(validation).completedAt()!=null);
            assertEquals("WORKLOAD_BUSY",validations.get(validation).errorReason());
            var rejected=assertThrows(RuntimeException.class,()->experiments.submit(new PerformanceExperimentContract.Request(1,source,sql,null,"42",10)));
            assertTrue(rejected.getMessage().contains("WORKLOAD_BUSY"));
            verify(keychain,never()).retrieve(anyString());
            assertTrue(leases.snapshot().exclusiveActive());assertEquals(0,leases.snapshot().ordinaryTickets());
        }finally{ticket.release();sessions.delete(session);connections.delete(config.id());}
        assertFalse(leases.snapshot().exclusiveActive());
    }
    private void await(java.util.function.BooleanSupplier done)throws InterruptedException {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(!done.getAsBoolean()&&System.nanoTime()<end)Thread.sleep(10);
        assertTrue(done.getAsBoolean(),"production task did not publish its rejection");
    }
}
