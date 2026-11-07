package com.bitdata.sqlworkbench.optimization;

import com.bitdata.sqlworkbench.connection.*;
import com.bitdata.sqlworkbench.execution.*;
import com.bitdata.sqlworkbench.testsupport.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import java.lang.reflect.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static com.bitdata.sqlworkbench.optimization.PerformanceExperimentContract.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real JDBC/runner integration. Stop and setting changes are explicitly controlled test hooks. */
@Tag("integration")
class PerformanceExperimentRunnerIntegrationTest {
    private final AtomicInteger evidenceIndex=new AtomicInteger();
    @Test @RequiresTestDatabase(Dialect.POSTGRESQL)
    void postgresRunnerBudgetsStopsAndEnvironment() throws Exception { matrix(Dialect.POSTGRESQL); }
    @Test @RequiresTestDatabase(Dialect.MYSQL)
    void mysqlRunnerBudgetsStopsAndEnvironment() throws Exception { matrix(Dialect.MYSQL); }

    private void matrix(Dialect dialect) throws Exception {
        String d=dialect==Dialect.POSTGRESQL?"postgresql":"mysql";
        String table=dialect==Dialect.POSTGRESQL?"public.employees":"employees.employees";
        String all="SELECT e.emp_no FROM "+table+" AS e";
        var initial=TestDatabaseResolver.resolve(dialect);
        try(Connection probe=DriverManager.getConnection(TestDatabaseResolver.buildJdbcUrl(dialect,initial),initial.username(),initial.password())){
            assertEquals("ROW_BUDGET_EXCEEDED",assertThrows(IllegalArgumentException.class,()->ExactResultBag.read(probe,d,all,new ValidationControl(120))).getMessage());
        }
        Report perRead=run(dialect,all,"NONE");
        assertEquals("ROW_BUDGET_EXCEEDED",perRead.errorReason());assertTrue(perRead.samples().isEmpty());
        String three="SELECT e.emp_no, e.emp_no AS c_two, e.emp_no AS c_three FROM "+table+" AS e";
        assertEquals("CELL_COUNT_BUDGET_EXCEEDED",run(dialect,three,"NONE").errorReason());
        String bounded=all+" WHERE e.emp_no >= 10001 AND e.emp_no <= 110000";
        Report cumulative=run(dialect,bounded,"NONE");
        assertEquals("EXPERIMENT_ROW_BUDGET_EXCEEDED",cumulative.errorReason());
        assertEquals("MATCH",cumulative.validation().verdict());assertEquals(9,cumulative.samples().size());
        assertEquals(8,cumulative.samples().stream().filter(s->s.status().equals("COMPLETE")).count());
        assertEquals("NOT_AVAILABLE",cumulative.summary().state());
        String projections=java.util.stream.IntStream.range(0,32).mapToObj(n->"e.emp_no AS c_"+n).collect(java.util.stream.Collectors.joining(", "));
        String wide="SELECT "+projections+" FROM "+table+" AS e";
        Report cellTotal=run(dialect,wide+" WHERE e.emp_no >= 10001 AND e.emp_no <= 17812","NONE");
        assertEquals("EXPERIMENT_CELL_BUDGET_EXCEEDED",cellTotal.errorReason());
        assertEquals(19,cellTotal.samples().size());
        String smallWide=wide+" WHERE e.emp_no <= 10020";
        assertEquals("COMPLETE",run(dialect,smallWide,"NONE").comparisonState());
        assertEquals("COLUMN_BUDGET_EXCEEDED",run(dialect,"SELECT "+projections+", e.emp_no AS extra_col FROM "+table+" AS e WHERE e.emp_no<=10020","NONE").errorReason());
        String small=all+" WHERE e.emp_no <= 10020";
        for(String stop:List.of("CANCELLED","DEADLINE_EXCEEDED")){
            Report report=run(dialect,small,stop);
            assertEquals(stop,report.errorReason());assertEquals(1,report.samples().size());
            assertEquals(stop.equals("CANCELLED")?"CANCELLED":"TIMED_OUT",report.status());
            assertEquals("NOT_AVAILABLE",report.summary().state());
        }
        for(String planMode:List.of("PLAN_PERMISSION","PLAN_CANCEL","PLAN_DEADLINE")){
            Report plan=run(dialect,small,planMode);
            assertEquals("COMPLETE",plan.comparisonState());assertEquals(24,plan.samples().size());
            assertEquals("DESCRIPTIVE",plan.summary().state());assertTrue(plan.actualPlans().degraded());
            if(planMode.equals("PLAN_PERMISSION")){
                assertEquals("COMPLETED",plan.status());
                assertNotEquals("FAILED",plan.actualPlans().original().status());
                assertEquals("ACTUAL_PLAN_CAPTURE_FAILED",plan.actualPlans().candidate().errorReason());
            }else assertEquals(planMode.equals("PLAN_CANCEL")?"CANCELLED":"TIMED_OUT",plan.status());
        }
        Report drift=run(dialect,small,"ENVIRONMENT");
        assertEquals("ENVIRONMENT_CHANGED",drift.errorReason());assertFalse(drift.environment().comparable());
        assertEquals(24,drift.samples().size());assertEquals("NOT_AVAILABLE",drift.summary().state());
        // Isolated, clearly synthetic fixture for encoded-byte boundaries; dropped in finally.
        String name="perf04_bytes_"+UUID.randomUUID().toString().replace("-","").substring(0,12);
        var entry=TestDatabaseResolver.resolve(dialect);
        try(Connection setup=DriverManager.getConnection(TestDatabaseResolver.buildJdbcUrl(dialect,entry),entry.username(),entry.password())){
            try(Statement statement=setup.createStatement()){statement.execute("CREATE TABLE "+name+" (x INTEGER, v TEXT)");}
            try{
                try(PreparedStatement insert=setup.prepareStatement("INSERT INTO "+name+" VALUES (?,?)")){
                    for(int n=0;n<500;n++){insert.setInt(1,n);insert.setString(2,"x".repeat(40000));insert.addBatch();}
                    insert.executeBatch();
                }
                String schema=dialect==Dialect.POSTGRESQL?"public":"employees";
                Report byteLimit=run(dialect,"SELECT e.v FROM "+schema+"."+name+" AS e","NONE");
                assertEquals("BYTE_BUDGET_EXCEEDED",byteLimit.errorReason());assertTrue(byteLimit.samples().isEmpty());
                Report totalBytes=run(dialect,"SELECT e.v FROM "+schema+"."+name+" AS e WHERE e.x<140","NONE");
                assertEquals("EXPERIMENT_BYTE_BUDGET_EXCEEDED",totalBytes.errorReason());
                assertEquals(22,totalBytes.samples().size());
                try(Statement statement=setup.createStatement()){statement.execute("DELETE FROM "+name);}
                try(PreparedStatement insert=setup.prepareStatement("INSERT INTO "+name+" VALUES (1,?)")){
                    insert.setString(1,"x".repeat(dialect==Dialect.POSTGRESQL?65536:65535));insert.executeUpdate();
                }
                assertEquals("COMPLETE",run(dialect,"SELECT e.v FROM "+schema+"."+name+" AS e","NONE").comparisonState());
                if(dialect==Dialect.POSTGRESQL){
                    try(PreparedStatement update=setup.prepareStatement("UPDATE "+name+" SET v=?")){update.setString(1,"x".repeat(65537));update.executeUpdate();}
                    assertEquals("CELL_BUDGET_EXCEEDED",run(dialect,"SELECT e.v FROM "+schema+"."+name+" AS e","NONE").errorReason());
                }
            }finally{
                try(Statement statement=setup.createStatement()){statement.execute("DROP TABLE "+name);}
                try(PreparedStatement remaining=setup.prepareStatement("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=? AND table_name=?")){
                    remaining.setString(1,dialect==Dialect.POSTGRESQL?"public":"employees");remaining.setString(2,name);
                    try(ResultSet rows=remaining.executeQuery()){assertTrue(rows.next());assertEquals(0,rows.getInt(1));}
                }
                System.out.println("PERF04_FIXTURE_CLEAN "+d+" "+name);
            }
        }
    }

    private Report run(Dialect dialect,String sql,String mode)throws Exception {
        var entry=TestDatabaseResolver.resolve(dialect);
        String d=dialect==Dialect.POSTGRESQL?"postgresql":"mysql";
        var leases=new WorkloadLeaseManager();
        try(Connection physical=DriverManager.getConnection(TestDatabaseResolver.buildJdbcUrl(dialect,entry),entry.username(),entry.password())){
            var connections=mock(ConnectionService.class);
            var store=mock(PerformanceExperimentReportStore.class);
            var capture=new PerformanceExperimentPlanCapture(DatabaseStrategyFactory.withBuiltinStrategies(),new ExplainServiceFactory(List.of(new PostgreSqlPlanAdapter(),new MySqlPlanAdapter())));
            try(RunnerHandle runner=new RunnerHandle(new PerformanceExperimentTasks(connections,mock(OptimizationSources.class),store,new ObjectMapper(),capture))){
                var tasks=runner.tasks;var lease=leases.acquireExclusive();
                var source=new OptimizationSql.Source("synthetic-session","revision",sql,"connection","config",d);
                var task=tasks.new Task(new Request(1,source,sql,null,"42",120),"42",lease);
                AtomicInteger reads=new AtomicInteger(),plans=new AtomicInteger();
                Connection intercepted=(Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
                    try{
                        if(method.getName().equals("prepareStatement")&&args.length==3){
                            int read=reads.incrementAndGet();
                            if(read==3&&mode.equals("CANCELLED"))task.control.cancelled.set(true);
                            if(read==3&&mode.equals("DEADLINE_EXCEEDED"))org.springframework.test.util.ReflectionTestUtils.setField(task.control,"deadline",System.nanoTime()-1);
                            if(read==26&&mode.equals("ENVIRONMENT"))try(Statement statement=physical.createStatement()){
                                statement.execute(d.equals("postgresql")?"SET LOCAL enable_hashjoin=off":"SET SESSION time_zone='+01:00'");
                            }
                        }
                        if(method.getName().equals("createStatement")&&mode.startsWith("PLAN_")){
                            Statement statement=(Statement)method.invoke(physical,args);
                            return Proxy.newProxyInstance(Statement.class.getClassLoader(),new Class<?>[]{Statement.class},(statementProxy,statementMethod,statementArgs)->{
                                try{
                                    if(statementMethod.getName().equals("executeQuery")&&((String)statementArgs[0]).startsWith("EXPLAIN")){
                                        int plan=plans.incrementAndGet();
                                        if(mode.equals("PLAN_PERMISSION")&&plan==2)throw new SQLException("synthetic permission rejection",d.equals("postgresql")?"42501":"42000",d.equals("mysql")?1142:0);
                                        if(mode.equals("PLAN_CANCEL"))task.control.cancelled.set(true);
                                        if(mode.equals("PLAN_DEADLINE"))org.springframework.test.util.ReflectionTestUtils.setField(task.control,"deadline",System.nanoTime()-1);
                                        task.control.check();
                                    }
                                    return statementMethod.invoke(statement,statementArgs);
                                }catch(InvocationTargetException failure){throw failure.getCause();}
                            });
                        }
                        return method.invoke(physical,args);
                    }catch(InvocationTargetException failure){throw failure.getCause();}
                });
                when(connections.openJdbcConnection(anyString(),anyString(),any())).thenReturn(leases.track(intercepted,lease.retain()));
                task.run();Report report=task.report;
                assertNotNull(report.completedAt());assertFalse(leases.snapshot().exclusiveActive());
                verify(store).save(report);
                if(mode.equals("CANCELLED")||mode.equals("DEADLINE_EXCEEDED"))assertEquals(3,reads.get());
                if(report.errorReason()!=null&&report.errorReason().equals("EXPERIMENT_ROW_BUDGET_EXCEEDED"))assertEquals(11,reads.get());
                java.nio.file.Path evidence=java.nio.file.Path.of("target/perf04-live");java.nio.file.Files.createDirectories(evidence);
                java.nio.file.Files.writeString(evidence.resolve(d+"-"+String.format("%02d",evidenceIndex.incrementAndGet())+"-"+mode+".json"),new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(report));
                System.out.println("PERF04_LIVE "+d+" "+mode+" "+report.status()+" "+report.comparisonState()+" "+report.errorReason()+" reads="+reads.get()+" samples="+report.samples().size());
                return report;
            }
        }finally{leases.close();}
    }
    private record RunnerHandle(PerformanceExperimentTasks tasks) implements AutoCloseable {public void close(){tasks.close();}}
}
