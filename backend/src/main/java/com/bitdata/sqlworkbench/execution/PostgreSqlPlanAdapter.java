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

/**
 * Parses PostgreSQL EXPLAIN (ANALYZE true, FORMAT JSON) output into
 * a tree of PlanNode objects for the PlanView UI.
 *
 * <p>Expected input format: {@code [{"Plan": {...}}]}
 */
@Component
public class PostgreSqlPlanAdapter implements ExplainService {

    private static final Logger log = LoggerFactory.getLogger(PostgreSqlPlanAdapter.class);
    private final ObjectMapper mapper = new ObjectMapper().enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    @Override
    public String dialect() {
        return "postgresql";
    }

    @Override
    public PlanNode parse(String explainJson, String dialect) {
        if (explainJson == null || explainJson.isBlank()) {
            return null;
        }
        try {
            JsonNode root = mapper.readTree(explainJson);
            if (root.isArray() && !root.isEmpty()) {
                JsonNode planNode = root.get(0).get("Plan");
                if (planNode != null) {
                    return parseNode(planNode, "$[0].Plan");
                }
            }
            return null;
        } catch (Exception e) {
            log.warn("Failed to parse PostgreSQL EXPLAIN JSON: {}", e.getMessage());
            return null;
        }
    }

    private PlanNode parseNode(JsonNode node, String path) {
        if(!node.isObject() || !node.path("Node Type").isTextual()) return new PlanNode("Unknown PostgreSQL node",0,0,0,0,0,List.of(),null,null,metrics(node,path));
        String operation = node.path("Node Type").asText("Unknown");

        // "Relation Name" is the structured scan target — the ONLY place the
        // table appears on this channel (operation is the bare node type;
        // "Seq Scan on <table>" exists solely in psql text EXPLAIN). Pinned by
        // a live probe on the e2e DB 2026-09-09; PG does NOT emit "Table Name"
        // (task-e-report §3.4's assumption, corrected by recon). Scans of CTEs
        // or subqueries carry "Relationship Name" instead — deliberately not
        // taken: this finding is about physical tables.
        JsonNode tableNameNode = node.get("Relation Name");
        String tableName = tableNameNode != null && tableNameNode.isTextual()
            && !tableNameNode.asText().isBlank() ? tableNameNode.asText() : null;

        double startupCost = node.path("Startup Cost").asDouble(0.0);
        double totalCost = node.path("Total Cost").asDouble(0.0);
        long planRows = node.path("Plan Rows").asLong(0);
        long actualRows = node.path("Actual Rows").asLong(0);
        long actualDuration = (long) node.path("Actual Total Time").asDouble(0.0);

        List<PlanNode> children = new ArrayList<>();
        JsonNode plans = node.get("Plans");
        if (plans != null && plans.isArray()) {
            for (JsonNode child : plans) {
                children.add(parseNode(child,path+".Plans["+children.size()+"]"));
            }
        }

        // Structured predicate text. Each field is nullable: null means
        // "PG did not emit this predicate for this node", NOT "no predicate
        // exists". Empty strings are coerced to null so downstream rules see
        // a single "absent" representation regardless of channel quirks.
        PlanNodeConditions conditions = new PlanNodeConditions(
            textOrNull(node.get("Filter")),
            textOrNull(node.get("Index Cond")),
            textOrNull(node.get("Hash Cond")),
            textOrNull(node.get("Join Filter"))
        );

        if(plans != null && !plans.isArray()) children.add(new PlanNode("Unknown Plans",0,0,0,0,0,List.of(),null,null,metrics(node,path+".Plans")));
        return new PlanNode(operation, startupCost, totalCost,
            planRows, actualRows, actualDuration, children, tableName, conditions, metrics(node,path));
    }

    private static NodeMetrics metrics(JsonNode node,String path) {
        Metric loops=Metric.read(node,"Actual Loops","LOOPS","TOTAL");
        Metric rows=Metric.read(node,"Actual Rows","ROWS","PER_LOOP_AVERAGE");
        Metric time=Metric.read(node,"Actual Total Time","MILLISECONDS","PER_LOOP_AVERAGE");
        if(loops.available() && loops.decimal().signum()==0) {
            rows=Metric.absent("NOT_EXECUTED","ROWS","PER_LOOP_AVERAGE","Actual Rows","NODE_NOT_EXECUTED");
            time=Metric.absent("NOT_EXECUTED","MILLISECONDS","PER_LOOP_AVERAGE","Actual Total Time","NODE_NOT_EXECUTED");
        }
        return new NodeMetrics(1,"DATABASE_NODE",path,
            Metric.read(node,"Plan Rows","ROWS","PER_INVOCATION"),rows,time,loops,
            Metric.read(node,"Startup Cost","COST","NODE_ESTIMATE"),Metric.read(node,"Total Cost","COST","NODE_ESTIMATE"));
    }

    private static String textOrNull(JsonNode node) {
        if (node == null || node.isNull() || !node.isTextual()) {
            return null;
        }
        String text = node.asText();
        return text == null || text.isBlank() ? null : text;
    }
}
