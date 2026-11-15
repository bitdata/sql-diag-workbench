package com.bitdata.sqlworkbench.diagnostics.rules;

import com.bitdata.sqlworkbench.diagnostics.*;
import com.bitdata.sqlworkbench.execution.model.*;
import com.bitdata.sqlworkbench.execution.model.ExecutionResult.*;
import com.bitdata.sqlworkbench.sql.model.FragmentNode;
import org.springframework.stereotype.Component;
import java.util.*;

/** Observations and next checks, never a proof of a unique performance cause. */
@Component
public class PlanInvestigationRule implements DiagnosticRule {
    public String id(){return "plan-investigation";}
    public String displayName(){return "Plan investigation";}
    public String description(){return "Locate observed repeated execution and scan, filter, sort, aggregation or materialization structures.";}
    public Severity severity(){return Severity.LOW;}
    public List<DiagnosticFinding> analyze(FragmentNode fragment,PlanNode plan,Timing timing) {
        var findings=new ArrayList<DiagnosticFinding>();
        PlanNodeWalker.walk(plan,node->{
            var m=node.metrics();if(m==null||!"DATABASE_NODE".equals(m.origin())||m.sourcePath()==null)return;
            String operation=node.operation().toLowerCase(Locale.ROOT);
            boolean oracle=m.sourcePath().startsWith("oracle/id/");
            boolean repeated=!oracle&&m.loops().available()&&m.loops().decimal().compareTo(java.math.BigDecimal.ONE)>0;
            boolean ordering=operation.matches(".*(?:sort|aggregate|aggregation|group|window|materializ|temporary).*"),scan=operation.contains("scan")||operation.startsWith("filter");
            boolean oracleStructure=oracle&&(operation.startsWith("table access")||operation.startsWith("index ")||operation.contains("join")||operation.startsWith("nested loops")||operation.startsWith("connect by"));
            if(!repeated&&!ordering&&!scan&&!oracleStructure)return;
            String message=repeated?"This iterator ran more than once. Inspect per-loop work and its calling context before attributing query cost.":
                ordering?"Inspect this sorting, aggregation or materialization step in the original query. Inclusive times cannot be added; missing memory or disk evidence does not prove a spill.":
                "Inspect the scan or filter and its available output metrics. A scan alone does not prove that an index would help; output rows do not establish input size or selectivity.";
            findings.add(DiagnosticFinding.builder().id(id()+"-"+fragment.id()+"-"+findings.size()).ruleId(id()).severity(Severity.LOW)
                .title(repeated?"Repeated iterator execution":"Plan structure observation").message(oracle?"Inspect this Oracle plan structure and its separately reported native fields. Counts and inclusive times do not establish selectivity, an index recommendation or a unique bottleneck.":message)
                .evidence(List.of("Operation: "+node.operation(),"Source: "+m.sourcePath(),"Estimated rows: "+text(m.estimatedRows()),"Actual rows: "+text(m.actualRows()),"Node time ms: "+text(m.actualDurationMs()),"Loops: "+text(m.loops())))
                .confidence(1).affectedNodeId(fragment.id()).planNodePath(m.sourcePath()).build());
        });
        return List.copyOf(findings);
    }
    private static String text(Metric m){return (m.available()?m.value():m.state()+" ("+m.errorReason()+")")+" · "+m.basis();}
}
