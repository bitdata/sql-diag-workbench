package com.bitdata.sqlworkbench.execution;

import com.bitdata.sqlworkbench.execution.model.ExecutionResult.PlanNode;
import com.bitdata.sqlworkbench.execution.model.ExecutionResult.PlanNodeConditions;
import com.fasterxml.jackson.databind.JsonNode;
import com.bitdata.sqlworkbench.execution.model.*;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Parses the supported MySQL estimated EXPLAIN FORMAT=JSON output
 * into the common PlanNode tree structure.
 *
 * <p>MySQL's JSON structure differs significantly from PostgreSQL:
 * <ul>
 *   <li>Root is {@code query_block} (not a {@code [Plan]} array)</li>
 *   <li>Access types (ALL, ref, eq_ref) are mapped to PG canonical names</li>
 *   <li>Joins use {@code nested_loop} wrapper nodes</li>
 *   <li>No per-node timing — {@code actualDuration} is always 0</li>
 * </ul>
 */
@Component
public class MySqlPlanAdapter implements ExplainService {

    private static final Logger log = LoggerFactory.getLogger(MySqlPlanAdapter.class);
    private final ObjectMapper mapper = new ObjectMapper().enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    /**
     * MySQL access_type → PostgreSQL canonical operation name.
     * This normalization allows diagnostic rules to match without modification.
     */
    private static final Map<String, String> OPERATION_MAP = Map.ofEntries(
        // Scan types (access_type)
        Map.entry("all",          "Seq Scan"),
        Map.entry("index",        "Index Scan"),
        Map.entry("range",        "Index Scan"),
        Map.entry("ref",          "Index Scan"),
        Map.entry("eq_ref",       "Index Scan"),
        Map.entry("const",        "Index Scan"),
        Map.entry("system",       "Index Scan"),
        // Join types
        Map.entry("nested_loop",  "Nested Loop"),
        Map.entry("hash_join",    "Hash Join"),
        Map.entry("merge_join",   "Merge Join"),
        // Other operations
        Map.entry("filesort",     "Sort"),
        Map.entry("temporary",    "Materialize"),
        Map.entry("union",        "Append")
    );

    @Override
    public String dialect() {
        return "mysql";
    }

    @Override
    public PlanNode parse(String explainJson,String dialect) {
        if(explainJson==null || explainJson.isBlank()) return null;
        try {
            JsonNode root=mapper.readTree(explainJson);
            return root.has("query_block") ? block(root.get("query_block"),"$.query_block") : null;
        } catch(Exception e) { return null; } // parseDetailed exposes FAILED; never consumed as success.
    }

    private PlanNode block(JsonNode block,String path) {
        List<PlanNode> children=new ArrayList<>();
        String operation="Query Block";
        var fields=block.fields();
        while(fields.hasNext()) {
            var entry=fields.next(); String key=entry.getKey(); JsonNode value=entry.getValue();
            String childPath=path+"."+key;
            switch(key) {
                case "table" -> children.add(table(value,childPath));
                case "nested_loop" -> {
                    List<PlanNode> joins=new ArrayList<>();
                    if(value.isArray()) for(int i=0;i<value.size();i++) joins.add(block(value.get(i),childPath+"["+i+"]"));
                    else joins.add(synthetic("Unknown nested_loop",List.of(),value,childPath));
                    children.add(synthetic("Nested Loop",joins,value,childPath));
                }
                case "query_specifications" -> {
                    operation="Append";
                    if(value.isArray()) for(int i=0;i<value.size();i++) children.add(block(value.get(i),childPath+"["+i+"]"));
                    else children.add(synthetic("Unknown query_specifications",List.of(),value,childPath));
                }
                case "union_result","query_block","ordering_operation","grouping_operation","duplicates_removal","buffer_result","materialized_from_subquery" -> children.add(block(value,childPath));
                case "select_id","cost_info","using_temporary_table","using_filesort","dependent","cacheable" -> { }
                case "message" -> operation="Optimizer: "+value.asText();
                default -> {
                    // Unknown output remains visible, with PARTIAL coverage rather than silent dropping.
                    children.add(synthetic("Unknown "+key,List.of(),value,childPath));
                }
            }
        }
        if(children.isEmpty() && operation.equals("Query Block")) operation="Unknown query block";
        if(children.size()==1 && operation.equals("Query Block")) return children.get(0);
        return synthetic(operation,children,block,path);
    }

    private PlanNode table(JsonNode table,String path) {
        String access=table.path("access_type").asText("unknown").toLowerCase(java.util.Locale.ROOT);
        String operation=OPERATION_MAP.getOrDefault(access,"Unknown access "+access);
        List<PlanNode> children=new ArrayList<>();
        if(table.has("attached_condition")) children.add(new PlanNode("Filter",0,0,0,0,0,List.of(),null,
            new PlanNodeConditions(table.get("attached_condition").asText(),null,null,null),syntheticMetrics(table,path+".attached_condition")));
        for(String field:List.of("materialized_from_subquery","attached_subqueries")) if(table.has(field)) {
            JsonNode value=table.get(field);
            if(value.isArray()) for(int i=0;i<value.size();i++) children.add(block(value.get(i),path+"."+field+"["+i+"]"));
            else children.add(block(value,path+"."+field));
        }
        Metric rows=Metric.read(table,"rows_examined_per_scan","ROWS","PER_INVOCATION");
        Metric cost=Metric.read(table.path("cost_info"),"prefix_cost","COST","PREFIX_ESTIMATE");
        var metrics=new NodeMetrics(1,"DATABASE_NODE",path,rows,
            Metric.absent("NOT_COLLECTED","ROWS","UNKNOWN","actual_rows","METRIC_NOT_PROVIDED"),
            Metric.absent("NOT_COLLECTED","MILLISECONDS","UNKNOWN",null,"METRIC_NOT_PROVIDED"),
            Metric.absent("NOT_COLLECTED","LOOPS","UNKNOWN",null,"METRIC_NOT_PROVIDED"),
            Metric.absent("NOT_COLLECTED","COST","UNKNOWN",null,"METRIC_NOT_PROVIDED"),cost);
        return new PlanNode(operation,0,cost.available()?cost.decimal().doubleValue():0,
            rows.available()?rows.decimal().longValue():0,0,0,children,table.path("table_name").asText(null),PlanNodeConditions.EMPTY,metrics);
    }

    private PlanNode synthetic(String operation,List<PlanNode> children,JsonNode value,String path) {
        return new PlanNode(operation,0,0,0,0,0,children,null,PlanNodeConditions.EMPTY,syntheticMetrics(value,path));
    }
    private NodeMetrics syntheticMetrics(JsonNode value,String path) {
        return new NodeMetrics(1,"APPLICATION_SYNTHETIC",path,unknown("ROWS"),unknown("ROWS"),unknown("MILLISECONDS"),
            unknown("LOOPS"),unknown("COST"),Metric.read(value.path("cost_info"),"query_cost","COST","QUERY_ESTIMATE"));
    }
    private Metric unknown(String unit) { return Metric.absent("UNKNOWN",unit,"UNKNOWN",null,"SYNTHETIC_NODE"); }
}
