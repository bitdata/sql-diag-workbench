package com.bitdata.sqlworkbench.execution;

import com.bitdata.sqlworkbench.execution.model.*;
import com.fasterxml.jackson.databind.*;
import java.util.*;

/** Oracle uses structured plan rows; actual collection is never EXPLAIN PLAN. */
@org.springframework.stereotype.Component
public final class OraclePlanAdapter implements ExplainService {
    public String dialect(){return "oracle";}
    public ExecutionResult.PlanNode parse(String raw,String dialect){return parseDetailed(raw,dialect,"ORACLE_PLAN_ROWS").plan();}
    @Override public Parsed parseDetailed(String raw,String dialect,String channel){
        if(!"oracle".equals(dialect)||!"ORACLE_PLAN_ROWS".equals(channel))return ExplainService.failed("PLAN_CHANNEL_UNSUPPORTED");
        if(raw==null||raw.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>OraclePlanReadLimits.RAW_BYTES)return ExplainService.failed("PLAN_RAW_LIMIT");
        try{
            var parsed=OraclePlanRowsParser.parse(new ObjectMapper().readTree(raw),"estimated","VERIFIED_ESTIMATED");
            return new Parsed(parsed.plan(),parsed.parseStatus(),parsed.issues().stream().map(code->new PlanCapture.Issue(code,null,"Oracle plan evidence is incomplete")).toList());
        }catch(com.fasterxml.jackson.core.JsonProcessingException invalid){return ExplainService.failed("PLAN_PARSE_FAILED");}
    }
    static PlanDetails details(OraclePlanCollector.Outcome outcome,PlanEvidence.Environment environment){
        var parsed=outcome.parsed();var nodes=new ArrayList<PlanDetails.NodeEvidence>();
        // Preserve exact projected row JSON independently of the rendered tree.
        try{
            if(parsed.rawRows()!=null)for(JsonNode row:new ObjectMapper().readTree(parsed.rawRows()))nodes.add(new PlanDetails.NodeEvidence("oracle/id/"+row.path("ID").asText(),row.toString(),
                Metric.absent("UNKNOWN","MILLISECONDS","UNKNOWN","firstRowMs","METRIC_NOT_PROVIDED")));
        }catch(com.fasterxml.jackson.core.JsonProcessingException invalid){
            return new PlanDetails(1,"ORACLE_PLAN_ROWS",parsed.rawRows(),environment.database(),environment.databaseUser(),environment.settings(),unknownTime("planningTime"),unknownTime("statementDuration"),List.of(),"NODE_EVIDENCE_ENCODING_FAILED",parsed.nativeEvidence());
        }
        var settings=new LinkedHashMap<>(environment.settings());settings.put("collectionMode",outcome.mode());settings.put("serverExecution","UNKNOWN");
        settings.put("bindingState",outcome.bindingState());settings.put("statementIsolation","DEDICATED_CONNECTION");
        if(outcome.mode().equals("actual")){
            settings.put("collectionStatisticsLevel","ALL");settings.put("sqlIsolation","UNIQUE_PREFIX_COMMENT_CHANGES_CURSOR");
            settings.put("sqlId",outcome.sqlId());settings.put("childNumber",outcome.childNumber());settings.put("executionId",outcome.executionId());settings.put("executionStartRaw",outcome.executionStart());
        }else settings.put("planTableDisposition","TASK_TEMP_ROWS_ROLLBACK_AND_DEDICATED_CLOSE");
        return new PlanDetails(1,"ORACLE_PLAN_ROWS",parsed.rawRows(),environment.database(),environment.databaseUser(),settings,unknownTime("planningTime"),unknownTime("statementDuration"),nodes,
            parsed.errorReason()!=null?parsed.errorReason():environment.errorReason(),parsed.nativeEvidence());
    }
    private static Metric unknownTime(String field){return Metric.absent("UNKNOWN","MILLISECONDS","UNKNOWN",field,"ORACLE_METRIC_SEMANTICS_NOT_CERTIFIED");}
}
