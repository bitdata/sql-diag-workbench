package com.bitdata.sqlworkbench.execution;

import com.bitdata.sqlworkbench.diagnostics.*;
import com.bitdata.sqlworkbench.diagnostics.mapping.PlanSqlMappingService;
import com.bitdata.sqlworkbench.diagnostics.rules.*;
import com.bitdata.sqlworkbench.execution.model.*;
import com.bitdata.sqlworkbench.sql.model.*;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticApplicationContext;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class OraclePlanContractTest {
    final ObjectMapper json=new ObjectMapper();
    @Test void combinedOperationMustFitTheLiveAndArchivedDecoderBudget() {
        var rows=json.createArrayNode();var row=rows.addObject();
        row.put("ID","0").putNull("PARENT_ID").put("OPERATION","X".repeat(1024)).put("OPTIONS","Y");
        var parsed=OraclePlanRowsParser.parse(rows,"estimated","VERIFIED_ESTIMATED");
        assertEquals("FAILED",parsed.parseStatus());assertEquals("OPERATION_TEXT_LIMIT",parsed.errorReason());
        assertNull(parsed.plan());assertNull(parsed.nativeEvidence());
        row.put("OPERATION","X".repeat(1022));
        var boundary=OraclePlanRowsParser.parse(rows,"estimated","VERIFIED_ESTIMATED");
        assertEquals(1024,boundary.plan().operation().length());
    }
    @Test void omittedOversizedRawEvidenceMustRemainAnObservableFailure() {
        var rows=json.createArrayNode();rows.addObject().put("ID","0").putNull("PARENT_ID")
            .put("OPERATION","X".repeat(OraclePlanRowsParser.MAX_RAW_BYTES+1));
        var parsed=OraclePlanRowsParser.parse(rows,"estimated","VERIFIED_ESTIMATED");
        assertEquals("PLAN_RAW_LIMIT",parsed.errorReason());assertNull(parsed.rawRows());
        var outcome=new OraclePlanCollector.Outcome("SELECT 1 FROM dual",null,"estimated","VERIFIED_ESTIMATED",null,null,null,null,parsed,"CONFIRMED");
        var details=assertDoesNotThrow(()->OraclePlanAdapter.details(outcome,new PlanEvidence.Environment(null,null,Map.of(),null)));
        assertEquals("PLAN_RAW_LIMIT",details.errorReason());assertNull(details.rawPlan());assertTrue(details.nodes().isEmpty());
    }
    @Test void frozenOracleRowsRetainPreciseEvidenceAndRejectInvalidStructure()throws Exception{
        for(JsonNode fixture:json.readTree(getClass().getResourceAsStream("/plans/oracle-native-rows.json"))){
            var parsed=OraclePlanRowsParser.parse(fixture.get("rows"),fixture.path("mode").asText(),fixture.path("evidenceState").asText());
            assertEquals(fixture.path("expectedStatus").asText(),parsed.parseStatus(),fixture.path("name").asText());
            if(fixture.has("expectedReason"))assertEquals(fixture.path("expectedReason").asText(),parsed.errorReason());
            if(parsed.plan()!=null){
                assertEquals("UNKNOWN",parsed.plan().metrics().actualRows().state());
                assertNotNull(parsed.nativeEvidence());
                assertEquals(fixture.get("rows"),json.readTree(parsed.rawRows()));
            }else assertNull(parsed.nativeEvidence());
        }
    }
    @Test void allEightRulesDeclareLimitedOracleCoverageAndDirectMappingRefuses()throws Exception{
        try(var context=new StaticApplicationContext()){
            for(var rule:List.of(new PlanInvestigationRule(),new SequentialScanRule(),new EstimatedVsActualRule(),new NonSargablePredicateRule(),new JoinAmplificationRule(),new ExpectedRowMissingAtSourceRule(),new ExpectedRowEliminatedAtFilterRule(),new ExpectedRowEliminatedAtJoinRule()))context.getBeanFactory().registerSingleton(rule.id(),rule);
            var registry=new RuleRegistry(context);registry.init();
            var subject=new FragmentNode("oracle-subject",FragmentType.ORIGINAL,"Submitted SQL","SELECT 1 FROM dual",true,null,0,null,List.of());
            for(JsonNode fixture:json.readTree(getClass().getResourceAsStream("/plans/oracle-native-rows.json"))){
                var parsed=OraclePlanRowsParser.parse(fixture.get("rows"),fixture.path("mode").asText(),fixture.path("evidenceState").asText());if(parsed.plan()==null)continue;
                var capture=new PlanCapture(1,"actual","actual",subject.sqlFragment(),subject.sqlFragment(),"synthetic",null,"oracle",null,null,null,null,parsed.parseStatus(),List.of());
                var result=new ExecutionResult("e",ExecutionStatus.COMPLETED,null,null,0,null,parsed.plan(),null,null).withCapture(capture);
                var evaluated=registry.evaluate(subject,new ExecutionEngine.PlanObservation("VERIFIED",null,result),"e");
                assertEquals(8,evaluated.evaluation().rules().size());assertTrue(evaluated.evaluation().degraded());
                assertTrue(evaluated.evaluation().rules().stream().noneMatch(r->r.status().equals("EVALUATED")));
                for(var finding:evaluated.findings()){assertEquals("plan-investigation",finding.ruleId());assertNull(finding.verificationSql());}
                assertTrue(registry.evaluate(subject,new ExecutionEngine.PlanObservation("MISMATCH","PLAN_OWNER_CHANGED",result),"e").findings().isEmpty());
                var refusal=new PlanSqlMappingService(null).map("e","oracle",subject.sqlFragment(),result,subject,evaluated.findings());
                assertTrue(refusal.degraded());assertTrue(refusal.edges().isEmpty());assertEquals("ORACLE_PLAN_MAPPING_UNSUPPORTED",refusal.errorReason());
            }
        }
    }
}
