package com.bitdata.sqlworkbench.execution;

import com.bitdata.sqlworkbench.connection.*;
import com.bitdata.sqlworkbench.execution.model.*;
import com.bitdata.sqlworkbench.diagnostics.rules.PlanInvestigationRule;
import com.bitdata.sqlworkbench.sql.SelectOnlyGuard;
import com.bitdata.sqlworkbench.sql.model.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PerformanceInvestigationTest {
    private final ObjectMapper json=new ObjectMapper();
    private final PlanEvidence.Environment environment=new PlanEvidence.Environment("db","user",Map.of("setting","value"),null);
    @Test void frozenLiveJdbcSamplesRetainExactMetricsAndLocalSources() throws Exception {
        var samples=json.readTree(getClass().getResourceAsStream("/plans/m2-live-contract.json"));
        assertEquals(12,samples.size());
        for(var sample:samples) {
            String id=sample.path("id").asText(),raw=sample.path("raw").asText();
            boolean mysql=id.startsWith("mysql"),tree=mysql&&!id.contains("estimated");
            ExplainService adapter=mysql?new MySqlPlanAdapter():new PostgreSqlPlanAdapter();
            String channel=tree?"MYSQL_TREE":mysql?"MYSQL_JSON":"POSTGRESQL_JSON";
            var parsed=adapter.parseDetailed(raw,mysql?"mysql":"postgresql",channel);
            assertNotNull(parsed.plan(),id+parsed.issues());
            assertNotEquals("FAILED",parsed.status(),id);
            var details=PlanEvidence.capture(raw,channel,parsed.plan(),environment);
            assertNull(details.errorReason(),id);
            assertEquals(raw,details.rawPlan());
            assertFalse(details.nodes().isEmpty(),id);
            assertTrue(details.nodes().stream().allMatch(n->n.raw()!=null),id);
            assertEquals(details,json.readValue(json.writeValueAsString(details),PlanDetails.class));
            if(tree) assertTrue(details.nodes().stream().allMatch(n->raw.contains(n.raw())));
            if(id.contains("loops")) assertTrue(walk(parsed.plan()).stream().anyMatch(n->"3".equals(n.metrics().loops().value())),id);
            if(id.equals("postgresql-actual")) assertEquals("TOTAL",details.executionTimeMs().basis());
        }
    }
    @Test void treeKeepsZeroNotExecutedScientificDecimalsAndNativeOperation() {
        var parsed=MysqlTreePlanParser.parse("-> Filter: (x > 0) (cost=0..2 rows=9007199254740993) (actual time=40e-6..0.2 rows=0 loops=3)\n    -> Index lookup on t (never executed)\n");
        assertEquals("COMPLETE",parsed.status());
        assertEquals("Filter: (x > 0)",parsed.plan().operation());
        assertEquals("0",parsed.plan().metrics().actualRows().value());
        assertEquals("9007199254740993",parsed.plan().metrics().estimatedRows().value());
        assertEquals("0.00004",MysqlTreePlanParser.firstRow("-> x (actual time=40e-6..0.2 rows=0 loops=3)").value());
        assertEquals("NOT_EXECUTED",parsed.plan().children().get(0).metrics().loops().state());
        assertEquals("NOT_EXECUTED",MysqlTreePlanParser.firstRow("-> x (actual time=0..0 rows=0 loops=0)").state());
        assertEquals("PARTIAL",MysqlTreePlanParser.parse("-> x (actual time=bad)\nnew format").status());
        assertEquals("PARSE_ERROR",Metric.exact("1e999999","ROWS","TOTAL","rows").state());
        assertEquals("PARTIAL",MysqlTreePlanParser.parse("-> x (actual time=0..1e999999 rows=1 loops=1)").status());
    }
    @Test void budgetsAndChannelMismatchFailWithoutFallback() {
        var mysql=new MySqlPlanAdapter();
        assertEquals("FAILED",mysql.parseDetailed("-> x","mysql","MYSQL_JSON").status());
        assertEquals("FAILED",mysql.parseDetailed("{}","mysql","MYSQL_TREE").status());
        assertEquals("PLAN_CHANNEL_UNSUPPORTED",mysql.parseDetailed("{}","mysql","POSTGRESQL_JSON").issues().get(0).code());
        assertEquals("PLAN_TEXT_LIMIT_EXCEEDED",MysqlTreePlanParser.parse("x".repeat(262145)).issues().get(0).code());
        assertEquals("PLAN_NODE_LIMIT_EXCEEDED",MysqlTreePlanParser.parse("-> x\n".repeat(2049)).issues().get(0).code());
        String deep="";for(int i=0;i<98;i++)deep+=" ".repeat(i)+"-> x\n";
        assertEquals("PLAN_DEPTH_LIMIT_EXCEEDED",MysqlTreePlanParser.parse(deep).issues().get(0).code());
        assertEquals("PLAN_DEPTH_LIMIT_EXCEEDED",PlanLimits.jsonInputError("[".repeat(98)+"0"+"]".repeat(98)));
        assertNull(PlanEvidence.capture("x".repeat(262145),"MYSQL_TREE",null,environment).rawPlan());
    }
    @Test void investigationFindingsBindToNativeSourceWithoutInventingFragmentIdentity() {
        var plan=MysqlTreePlanParser.parse("-> Aggregate: count(0) (actual time=0.1..0.2 rows=1 loops=3)").plan();
        var subject=new FragmentNode("subject",FragmentType.ORIGINAL,"Original","SELECT 1",true,null,0,null,List.of());
        var finding=new PlanInvestigationRule().analyze(subject,plan,null).get(0).withoutFragment();
        assertEquals("tree[0]",finding.planNodePath());assertNull(finding.affectedNodeId());assertNull(finding.verificationSql());
        assertEquals("LOW",finding.severity().name());assertTrue(finding.message().contains("per-loop"));
    }
    @Test void mysqlActualUsesTreeAndCaptureErrorsStayObservable() throws Exception {
        var connections=mock(ConnectionService.class);var conn=mock(Connection.class);var metadata=mock(DatabaseMetaData.class);
        var stmt=mock(Statement.class);var rs=mock(ResultSet.class);
        when(connections.getById("c")).thenReturn(Optional.of(new ConnectionConfig("c","test","localhost",3306,"db","u","mysql",null,false,"","","direct",false)));
        when(connections.openJdbcConnection("c")).thenReturn(conn);when(conn.getMetaData()).thenReturn(metadata);
        when(metadata.getDatabaseProductName()).thenReturn("MySQL");when(metadata.getDatabaseProductVersion()).thenReturn("8.0.46");
        when(conn.createStatement()).thenReturn(stmt);when(stmt.executeQuery(anyString())).thenReturn(rs);when(rs.next()).thenReturn(true,false);
        var resultMetadata=mock(ResultSetMetaData.class);when(rs.getMetaData()).thenReturn(resultMetadata);when(resultMetadata.getColumnCount()).thenReturn(1);
        when(rs.getString(1)).thenReturn("-> Rows fetched before execution (actual time=0..0.1 rows=1 loops=1)");
        var factory=new ExplainServiceFactory(List.of(new MySqlPlanAdapter()));
        var engine=new ExecutionEngine(connections,factory,new DatabaseStrategyFactory(Map.of("mysql",new MySqlStrategy())),mock(SelectOnlyGuard.class),30,100);
        try {
            String id=engine.analyze("SELECT 1","c",3,100,"actual");
            for(int i=0;i<1000&&!engine.getStatus(id).status().isTerminal();i++)Thread.sleep(5);
            var result=engine.getStatus(id);assertEquals(ExecutionStatus.COMPLETED,result.status(),result.error());
            verify(stmt).executeQuery("EXPLAIN ANALYZE FORMAT=TREE SELECT 1");
            assertEquals("MYSQL_TREE",result.planCapture().details().channel());
            assertEquals("COMPLETE",result.planCapture().parseStatus());
            assertTrue(result.degraded());assertEquals("ENVIRONMENT_CAPTURE_FAILED",result.errorReason());
            assertTrue(result.planCapture().issues().stream().anyMatch(i->i.code().equals("ENVIRONMENT_CAPTURE_FAILED")));
            for(String shape:List.of("columns","rows","missing-metadata","empty")) {
                when(rs.getMetaData()).thenReturn(shape.equals("missing-metadata")?null:resultMetadata);
                when(resultMetadata.getColumnCount()).thenReturn(shape.equals("columns")?2:1);
                when(rs.next()).thenReturn(!shape.equals("empty"),shape.equals("rows"));
                String malformed=engine.analyze("SELECT 1","c",3,100,"actual");
                for(int i=0;i<1000&&!engine.getStatus(malformed).status().isTerminal();i++)Thread.sleep(5);
                var rejected=engine.getStatus(malformed);assertEquals("FAILED",rejected.planCapture().parseStatus(),shape);assertNull(rejected.plan());assertTrue(rejected.degraded());
                assertEquals(shape.equals("empty")?"PLAN_PARSE_FAILED":"PLAN_RESULT_SHAPE_UNSUPPORTED",rejected.errorReason());
            }
            when(metadata.getDatabaseProductVersion()).thenReturn("8.0.47");
            assertEquals("UNKNOWN",PlanCapabilityService.inspect("c","mysql",conn,factory).actual().state());
            when(metadata.getDatabaseProductVersion()).thenReturn("8.0.46");
            for(String product:List.of("PostgreSQL","MariaDB","Unknown")) {
                when(metadata.getDatabaseProductName()).thenReturn(product);
                assertEquals("UNSUPPORTED",PlanCapabilityService.inspect("c","mysql",conn,factory).actual().state());
            }
        } finally {engine.shutdown();}
    }
    private List<ExecutionResult.PlanNode> walk(ExecutionResult.PlanNode node) {
        var nodes=new ArrayList<ExecutionResult.PlanNode>();nodes.add(node);for(var child:node.children())nodes.addAll(walk(child));return nodes;
    }
}
