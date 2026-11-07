package com.bitdata.sqlworkbench.execution;

import com.bitdata.sqlworkbench.execution.model.ExecutionResult.PlanNode;
import com.fasterxml.jackson.databind.*;
import java.util.*;

/** Bounds are checked before recursive adapter/consumer traversal. */
public final class PlanLimits {
    public static final int MAX_CHARS=262144, MAX_NODES=2048, MAX_DEPTH=96, MAX_JSON_VALUES=32768;
    private PlanLimits() {}
    private record JsonEntry(JsonNode node,int depth) {}
    private record Entry(PlanNode node,int depth) {}
    public static String jsonInputError(String raw) {
        if(raw==null || raw.isBlank()) return "PLAN_PARSE_FAILED";
        if(raw.length()>MAX_CHARS) return "PLAN_TEXT_LIMIT_EXCEEDED";
        try {
            var root=new ObjectMapper().readTree(raw); var todo=new ArrayDeque<JsonEntry>();todo.push(new JsonEntry(root,0));int count=0;
            while(!todo.isEmpty()) {
                var e=todo.pop();if(e.depth()>MAX_DEPTH)return "PLAN_DEPTH_LIMIT_EXCEEDED";
                if(++count>MAX_JSON_VALUES)return "PLAN_NODE_LIMIT_EXCEEDED";
                for(var child:e.node())todo.push(new JsonEntry(child,e.depth()+1));
            }
            return null;
        } catch(Exception e) {return "PLAN_PARSE_FAILED";}
    }
    public static String treeError(PlanNode root) {
        if(root==null)return "PLAN_PARSE_FAILED";
        var todo=new ArrayDeque<Entry>();todo.push(new Entry(root,0));int count=0;
        while(!todo.isEmpty()) {
            var e=todo.pop();if(e.depth()>MAX_DEPTH)return "PLAN_DEPTH_LIMIT_EXCEEDED";
            if(++count>MAX_NODES)return "PLAN_NODE_LIMIT_EXCEEDED";
            for(var child:e.node().children())todo.push(new Entry(child,e.depth()+1));
        }
        return null;
    }
}
