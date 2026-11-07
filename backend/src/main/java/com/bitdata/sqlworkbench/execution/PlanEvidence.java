package com.bitdata.sqlworkbench.execution;

import com.bitdata.sqlworkbench.execution.model.*;
import com.bitdata.sqlworkbench.execution.model.ExecutionResult.PlanNode;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.*;
import java.util.*;
import java.util.regex.*;

/** Capture-local raw evidence; never a cross-query operator/SQL identity. */
public final class PlanEvidence {
    private static final Pattern PATH=Pattern.compile("\\.([A-Za-z_][A-Za-z_0-9 ]*)|\\[(\\d+)\\]");
    private static final ObjectMapper JSON=new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    public record Environment(String database,String databaseUser,Map<String,String> settings,String errorReason) {}
    private PlanEvidence() {}
    public static Environment environment(Connection connection,String dialect,int timeout) {
        String database=null,user=null;
        try {
            database=connection.getCatalog();user=connection.getMetaData().getUserName();
            String sql="mysql".equals(dialect)?"SELECT @@session.sql_mode AS sql_mode, @@session.optimizer_switch AS optimizer_switch, @@session.transaction_isolation AS transaction_isolation":
                "SELECT current_setting('search_path') AS search_path, current_setting('work_mem') AS work_mem, current_setting('max_parallel_workers_per_gather') AS max_parallel_workers_per_gather";
            var values=new LinkedHashMap<String,String>();
            try(var stmt=connection.prepareStatement(sql)) {
                stmt.setQueryTimeout(Math.max(1,Math.min(timeout,5)));
                try(var rs=stmt.executeQuery()) {
                    if(!rs.next())throw new SQLException("No environment row");
                    for(int i=1;i<=rs.getMetaData().getColumnCount();i++) {var value=rs.getString(i);if(value!=null)values.put(rs.getMetaData().getColumnLabel(i),value);}
                }
            }
            return new Environment(database,user,Map.copyOf(values),database==null||user==null?"ENVIRONMENT_CAPTURE_PARTIAL":null);
        } catch(Exception e) {return new Environment(database,user,Map.of(),"ENVIRONMENT_CAPTURE_FAILED");}
    }
    public static PlanDetails capture(String raw,String channel,PlanNode plan,Environment environment) {
        Metric planning=missing("Planning Time"),execution=missing("Execution Time");
        String reason=environment.errorReason();var nodes=new ArrayList<PlanDetails.NodeEvidence>();
        if(raw==null||raw.length()>PlanLimits.MAX_CHARS)
            return new PlanDetails(1,channel,null,environment.database(),environment.databaseUser(),environment.settings(),planning,execution,List.of(),raw==null?"PLAN_PARSE_FAILED":"PLAN_TEXT_LIMIT_EXCEEDED");
        try {
            boolean tree="MYSQL_TREE".equals(channel);JsonNode json=tree?null:JSON.readTree(raw);String[] lines=tree?raw.split("\\R",-1):null;
            if("POSTGRESQL_JSON".equals(channel)&&json.isArray()&&!json.isEmpty()) {
                planning=Metric.read(json.get(0),"Planning Time","MILLISECONDS","TOTAL");execution=Metric.read(json.get(0),"Execution Time","MILLISECONDS","TOTAL");
            }
            var todo=new ArrayDeque<PlanNode>();if(plan!=null)todo.push(plan);var seen=new HashSet<String>();
            while(!todo.isEmpty()) {
                var node=todo.pop();var m=node.metrics();
                if(m!=null&&m.sourcePath()!=null&&seen.add(m.sourcePath())&& !"tree".equals(m.sourcePath())) {
                    String local=null;Metric first=Metric.absent("NOT_COLLECTED","MILLISECONDS","PER_LOOP_AVERAGE","Actual Startup Time","METRIC_NOT_PROVIDED");
                    if(tree) {
                        var path=m.sourcePath();int n=Integer.parseInt(path.substring(5,path.length()-1));
                        if(n>=0&&n<lines.length) {local=lines[n];first=MysqlTreePlanParser.firstRow(local);}
                    } else {
                        var source=resolve(json,m.sourcePath());
                        if(source!=null) {
                            if(source.isObject()) {ObjectNode shallow=JSON.createObjectNode();var fields=source.fields();while(fields.hasNext()) {var field=fields.next();if(localField(field.getValue())||field.getKey().equals("cost_info"))shallow.set(field.getKey(),field.getValue());}local=shallow.toPrettyString();}
                            else if(source.isValueNode())local=source.toString();
                            first="POSTGRESQL_JSON".equals(channel)?Metric.read(source,"Actual Startup Time","MILLISECONDS","PER_LOOP_AVERAGE"):first;
                            if("NOT_EXECUTED".equals(m.actualDurationMs().state()))first=Metric.absent("NOT_EXECUTED","MILLISECONDS","PER_LOOP_AVERAGE","Actual Startup Time","NODE_NOT_EXECUTED");
                        }
                    }
                    if(local==null && "DATABASE_NODE".equals(m.origin()))reason="NODE_SOURCE_UNAVAILABLE";
                    if(local!=null || "DATABASE_NODE".equals(m.origin()))nodes.add(new PlanDetails.NodeEvidence(m.sourcePath(),local,first));
                }
                for(int i=node.children().size()-1;i>=0;i--)todo.push(node.children().get(i));
            }
        } catch(Exception e) {reason="PLAN_EVIDENCE_UNAVAILABLE";}
        return new PlanDetails(1,channel,raw,environment.database(),environment.databaseUser(),environment.settings(),planning,execution,nodes,reason);
    }
    private static JsonNode resolve(JsonNode json,String path) {
        if(path==null||!path.startsWith("$"))return null;
        int offset=1;var matcher=PATH.matcher(path);var value=json;
        while(matcher.find()) {if(matcher.start()!=offset||value==null)return null;value=matcher.group(1)!=null?value.get(matcher.group(1)):value.get(Integer.parseInt(matcher.group(2)));offset=matcher.end();}
        return offset==path.length()?value:null;
    }
    private static boolean localField(JsonNode value) {
        if(value.isValueNode())return true;
        if(!value.isArray())return false;
        for(var item:value)if(!item.isValueNode())return false;
        return true;
    }
    private static Metric missing(String source) {return Metric.absent("NOT_COLLECTED","MILLISECONDS","TOTAL",source,"METRIC_NOT_PROVIDED");}
}
