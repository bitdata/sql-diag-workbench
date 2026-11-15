package com.bitdata.sqlworkbench.execution;

import com.bitdata.sqlworkbench.execution.model.*;
import com.bitdata.sqlworkbench.execution.model.ExecutionResult.PlanNode;
import com.fasterxml.jackson.databind.*;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Bounded Oracle structured plan rows. */
public final class OraclePlanRowsParser {
    static final int MAX_NODES=4096, MAX_DEPTH=128, MAX_RAW_BYTES=512*1024, MAX_FIELD=1024;
    static final ObjectMapper JSON=new ObjectMapper();
    public record Parsed(String parseStatus,boolean degraded,String errorReason,List<String> issues,
                         String rawRows,PlanNode plan,PlanDetails.NativeEvidence nativeEvidence) {}
    static final class Invalid extends RuntimeException { Invalid(String code){super(code);} }
    static void require(boolean value,String reason){if(!value)throw new Invalid(reason);}
    static String id(JsonNode value){
        require(value!=null&&value.isTextual(),"INVALID_NODE_ID");String s=value.textValue();
        require(s.length()<=10&&s.matches("0|[1-9][0-9]*"),"INVALID_NODE_ID");return s;
    }
    static String field(JsonNode row,String key,boolean required){
        JsonNode v=row.get(key);if(v==null||v.isNull()){require(!required,"MISSING_"+key);return null;}
        require(v.isTextual()&&v.textValue().length()<=MAX_FIELD,"INVALID_"+key);
        require(!required||!v.textValue().isBlank(),"MISSING_"+key);return v.textValue();
    }
    static Metric absent(String field,String unit,String basis,String reason){return Metric.absent("UNKNOWN",unit,basis,field,reason);}
    static Metric read(JsonNode row,String name,String unit,String basis,Set<String> issues){
        JsonNode v=row.get(name);if(v!=null&&!v.isNull()&&(!v.isTextual()||v.textValue().length()>MAX_FIELD)){
            issues.add("INVALID_METRIC_VALUE");return Metric.absent("PARSE_ERROR",unit,basis,name,"INVALID_METRIC_VALUE");
        }
        Metric m=Metric.read(row,name,unit,basis);if("PARSE_ERROR".equals(m.state()))issues.add("INVALID_METRIC_VALUE");return m;
    }
    public static Parsed parse(JsonNode rows,String mode,String evidenceState){
        String raw=null;var issues=new LinkedHashSet<String>();
        try{
            require(Set.of("actual","estimated").contains(mode),"INVALID_MODE");
            require(Set.of("VERIFIED_COMPLETE","VERIFIED_ESTIMATED","PARTIAL_FETCH","PERMISSION_DENIED","CURSOR_CHANGED","FULLTEXT_MISMATCH").contains(evidenceState),"INVALID_EVIDENCE_STATE");
            // Production collection must impose these limits while reading JDBC rows/LOBs too.
            require(rows!=null&&rows.isArray(),"INVALID_ROWS");
            require(rows.size()<=MAX_NODES,"PLAN_NODE_LIMIT");
            raw=JSON.writeValueAsString(rows);
            require(raw.getBytes(StandardCharsets.UTF_8).length<=MAX_RAW_BYTES,"PLAN_RAW_LIMIT");
            if(Set.of("PERMISSION_DENIED","CURSOR_CHANGED","FULLTEXT_MISMATCH").contains(evidenceState))throw new Invalid(evidenceState);
            require(mode.equals("actual")?!evidenceState.equals("VERIFIED_ESTIMATED"):evidenceState.equals("VERIFIED_ESTIMATED"),"MODE_EVIDENCE_MISMATCH");
            require(!rows.isEmpty(),"PLAN_ROWS_MISSING");
            var byId=new LinkedHashMap<String,JsonNode>();var children=new HashMap<String,List<String>>();String root=null;
            for(JsonNode row:rows){
                require(row.isObject(),"INVALID_ROW");String key=id(row.get("ID"));require(!byId.containsKey(key),"DUPLICATE_NODE_ID");
                String operation=field(row,"OPERATION",true),options=field(row,"OPTIONS",false);
                require(operation.length()+(options==null||options.isBlank()?0:1+options.length())<=MAX_FIELD,"OPERATION_TEXT_LIMIT");
                // Refuse unbounded/unknown payload columns; projected field names must be explicit.
                var keys=row.fieldNames();while(keys.hasNext()){
                    String k=keys.next();require(Set.of("ID","PARENT_ID","OPERATION","OPTIONS","CARDINALITY","COST","BYTES","TIME","LAST_STARTS","LAST_OUTPUT_ROWS","LAST_CR_BUFFER_GETS","LAST_DISK_READS","LAST_ELAPSED_TIME").contains(k),"UNEXPECTED_PLAN_FIELD");
                }
                byId.put(key,row);children.put(key,new ArrayList<>());
                require(row.has("PARENT_ID"),"MISSING_PARENT_ID");
                if(row.get("PARENT_ID").isNull()){require(root==null,"MULTIPLE_ROOTS");root=key;}
            }
            require(root!=null,"ROOT_MISSING");
            for(var e:byId.entrySet()){
                JsonNode p=e.getValue().get("PARENT_ID");if(p.isNull())continue;String parent=id(p);
                require(!e.getKey().equals(parent),"PLAN_CYCLE");require(byId.containsKey(parent),"PARENT_MISSING");children.get(parent).add(e.getKey());
            }
            var order=new ArrayList<String>();var depth=new HashMap<String,Integer>();var queue=new ArrayDeque<String>();queue.add(root);depth.put(root,0);
            while(!queue.isEmpty()){
                String key=queue.removeFirst();order.add(key);var next=children.get(key);next.sort(Comparator.comparing(BigInteger::new));
                for(String child:next){int d=depth.get(key)+1;require(d<=MAX_DEPTH,"PLAN_DEPTH_LIMIT");require(depth.putIfAbsent(child,d)==null,"PLAN_CYCLE");queue.addLast(child);}
            }
            require(order.size()==byId.size(),"DISCONNECTED_OR_CYCLIC_PLAN");
            boolean actual=mode.equals("actual")&&evidenceState.equals("VERIFIED_COMPLETE");
            if(evidenceState.equals("PARTIAL_FETCH"))issues.add("PARTIAL_FETCH");
            var plans=new HashMap<String,PlanNode>();var nativeNodes=new ArrayList<PlanDetails.NativeNode>();
            for(int index=order.size()-1;index>=0;index--){
                String key=order.get(index),path="oracle/id/"+key;JsonNode row=byId.get(key);
                String operation=field(row,"OPERATION",true),options=field(row,"OPTIONS",false);
                var metrics=new LinkedHashMap<String,Metric>();
                metrics.put("cardinality",read(row,"CARDINALITY","ROWS","NODE_ESTIMATE",issues));
                metrics.put("cost",read(row,"COST","OPTIMIZER_COST","NODE_ESTIMATE",issues));
                String[][] actualFields={{"lastStarts","LAST_STARTS","EXECUTIONS","REPORTED_LAST_EXECUTION"},{"lastOutputRows","LAST_OUTPUT_ROWS","ROWS","REPORTED_LAST_EXECUTION"},{"lastCrBufferGets","LAST_CR_BUFFER_GETS","READS","REPORTED_LAST_EXECUTION"},{"lastDiskReads","LAST_DISK_READS","READS","REPORTED_LAST_EXECUTION"},{"lastElapsedMicros","LAST_ELAPSED_TIME","MICROSECONDS","REPORTED_LAST_EXECUTION_INCLUSIVE"}};
                for(String[] f:actualFields){
                    Metric value=actual?read(row,f[1],f[2],f[3],issues):absent(f[1],f[2],f[3],mode.equals("estimated")?"ESTIMATED_NOT_EXECUTED":"PARTIAL_FETCH");
                    metrics.put(f[0],value);if(actual&&"NOT_COLLECTED".equals(value.state()))issues.add("METRIC_NOT_PROVIDED");
                }
                // Native LAST values never become PG loops/mean/exclusive duration or generic actuals.
                NodeMetrics common=new NodeMetrics(1,"DATABASE_NODE",path,metrics.get("cardinality"),absent("actualRows","ROWS","UNKNOWN","ORACLE_METRIC_SEMANTICS_NOT_CERTIFIED"),absent("actualDurationMs","MILLISECONDS","UNKNOWN","ORACLE_METRIC_SEMANTICS_NOT_CERTIFIED"),absent("loops","LOOPS","UNKNOWN","ORACLE_METRIC_SEMANTICS_NOT_CERTIFIED"),absent("startupCost","COST","UNKNOWN","METRIC_NOT_PROVIDED"),read(row,"COST","COST","NODE_ESTIMATE",issues));
                plans.put(key,new PlanNode(operation+(options==null||options.isBlank()?"":" "+options),0,0,0,0,0,children.get(key).stream().map(plans::get).toList(),null,null,common));
                var attributes=new LinkedHashMap<String,String>();attributes.put("ID",key);if(options!=null)attributes.put("OPTIONS",options);
                if(!row.get("PARENT_ID").isNull())attributes.put("PARENT_ID",row.get("PARENT_ID").textValue());
                nativeNodes.add(new PlanDetails.NativeNode(path,operation,null,attributes,metrics,List.of()));
            }
            // Stable order follows structured traversal, independent of incoming row order.
            Collections.reverse(nativeNodes);
            boolean degraded=!issues.isEmpty();return new Parsed(degraded?"PARTIAL":"COMPLETE",degraded,degraded?issues.iterator().next():null,List.copyOf(issues),raw,plans.get(root),new PlanDetails.NativeEvidence(1,List.copyOf(nativeNodes),List.of()));
        }catch(Invalid invalid){
            // No made-up partial tree after a structural/identity failure. Oversized evidence is not echoed.
            return new Parsed("FAILED",true,invalid.getMessage(),List.of(invalid.getMessage()),"PLAN_RAW_LIMIT".equals(invalid.getMessage())?null:raw,null,null);
        }catch(com.fasterxml.jackson.core.JsonProcessingException failure){
            return new Parsed("FAILED",true,"PLAN_SERIALIZATION_FAILED",List.of("PLAN_SERIALIZATION_FAILED"),null,null,null);
        }
    }
}
