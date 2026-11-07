package com.bitdata.sqlworkbench.execution;

import com.bitdata.sqlworkbench.execution.model.ExecutionResult.PlanNode;

/**
 * Dialect-aware parser for EXPLAIN output. Each database dialect provides
 * its own implementation that knows how to parse the dialect-specific JSON
 * structure into the common {@link PlanNode} tree.
 *
 * <p>Implementations are discovered at runtime via {@link ExplainServiceFactory}.
 */
public interface ExplainService {

    /**
     * Parse EXPLAIN JSON output into a PlanNode tree.
     *
     * @param explainJson raw JSON string from the database EXPLAIN command
     * @param dialect     database dialect (for routing; adapters may ignore it)
     * @return PlanNode tree root, or null if parsing fails
     */
    PlanNode parse(String explainJson, String dialect);

    /**
     * The dialect name this adapter handles (e.g., "postgresql", "mysql").
     * Used by {@link ExplainServiceFactory} for routing.
     */
    String dialect();

    record Parsed(PlanNode plan,String status,java.util.List<com.bitdata.sqlworkbench.execution.model.PlanCapture.Issue> issues) {
        public Parsed { issues=java.util.List.copyOf(issues); }
    }
    default Parsed parseDetailed(String json,String dialect) {
        String inputError=PlanLimits.jsonInputError(json);
        if(inputError!=null)return failed(inputError);
        PlanNode plan=parse(json,dialect);
        String treeError=PlanLimits.treeError(plan);
        if(treeError!=null)return failed(treeError);
        var issues=new java.util.ArrayList<com.bitdata.sqlworkbench.execution.model.PlanCapture.Issue>();
        if(plan==null) issues.add(new com.bitdata.sqlworkbench.execution.model.PlanCapture.Issue("PLAN_PARSE_FAILED",null,"No recognized plan tree"));
        inspect(plan,issues);
        return new Parsed(plan,plan==null?"FAILED":issues.isEmpty()?"COMPLETE":"PARTIAL",issues);
    }
    default Parsed parseDetailed(String raw,String dialect,String channel) {
        if(!("POSTGRESQL_JSON".equals(channel)&&"postgresql".equals(dialect))&&!("MYSQL_JSON".equals(channel)&&"mysql".equals(dialect)))return failed("PLAN_CHANNEL_UNSUPPORTED");
        return parseDetailed(raw,dialect);
    }
    static Parsed failed(String reason) {return new Parsed(null,"FAILED",java.util.List.of(new com.bitdata.sqlworkbench.execution.model.PlanCapture.Issue(reason,null,"Plan unavailable: "+reason)));}
    private static void inspect(PlanNode node,java.util.List<com.bitdata.sqlworkbench.execution.model.PlanCapture.Issue> issues) {
        if(node==null) return;
        var m=node.metrics();
        if(m==null || node.operation().startsWith("Unknown")) issues.add(new com.bitdata.sqlworkbench.execution.model.PlanCapture.Issue(
            "PLAN_PARTIAL",m==null?null:m.sourcePath(),"Unrecognized or incomplete plan structure"));
        if(m!=null) for(var value:java.util.List.of(m.estimatedRows(),m.actualRows(),m.actualDurationMs(),m.loops(),m.startupCost(),m.totalCost())) {
            if("PARSE_ERROR".equals(value.state())) issues.add(new com.bitdata.sqlworkbench.execution.model.PlanCapture.Issue(
                "INVALID_METRIC_VALUE",m.sourcePath(),"Invalid metric: "+value.sourceField()));
        }
        for(var child:node.children()) inspect(child,issues);
    }
}
