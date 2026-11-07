package com.bitdata.sqlworkbench.execution;

import com.bitdata.sqlworkbench.execution.model.*;
import com.bitdata.sqlworkbench.execution.model.ExecutionResult.PlanNode;
import java.math.BigDecimal;
import java.util.*;
import java.util.regex.*;

/** Native MySQL TREE iterator evidence; no guessed PostgreSQL operator mapping. */
public final class MysqlTreePlanParser {
    public static final int MAX_CHARS=262144, MAX_NODES=2048, MAX_DEPTH=96;
    private static final String NUM="([+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?)";
    private static final Pattern ACTUAL=Pattern.compile("\\s+\\(actual time="+NUM+"\\.\\."+NUM+" rows="+NUM+" loops="+NUM+"\\)\\s*$");
    private static final Pattern COST=Pattern.compile("\\s+\\(cost="+NUM+"(?:\\.\\."+NUM+")? rows="+NUM+"\\)\\s*$");
    private static final Pattern NEVER=Pattern.compile("\\s+\\(never executed\\)\\s*$");
    private static final Pattern LINE=Pattern.compile("^( *)(?:->) (.+)$");
    private record Entry(int indent,String path,String operation,NodeMetrics metrics,List<Entry> children) {}
    private MysqlTreePlanParser() {}
    public static ExplainService.Parsed parse(String raw) {
        var issues=new ArrayList<PlanCapture.Issue>();
        if(raw==null || raw.isBlank()) return failed("PLAN_PARSE_FAILED");
        if(raw.length()>MAX_CHARS) return failed("PLAN_TEXT_LIMIT_EXCEEDED");
        var roots=new ArrayList<Entry>(); var stack=new ArrayDeque<Entry>(); int count=0;
        String[] lines=raw.split("\\R",-1);
        for(int line=0;line<lines.length;line++) {
            if(lines[line].isBlank()) continue;
            var match=LINE.matcher(lines[line]); String path="tree["+line+"]";
            if(!match.matches()) { issues.add(new PlanCapture.Issue("PLAN_PARTIAL",path,"Unrecognized TREE line")); continue; }
            if(++count>MAX_NODES) return failed("PLAN_NODE_LIMIT_EXCEEDED");
            int indent=match.group(1).length();
            while(!stack.isEmpty() && stack.peek().indent()>=indent) stack.pop();
            if(stack.size()>=MAX_DEPTH) return failed("PLAN_DEPTH_LIMIT_EXCEEDED");
            if(indent>0 && stack.isEmpty()) issues.add(new PlanCapture.Issue("PLAN_PARTIAL",path,"Indented root without parent"));
            String op=match.group(2); var actual=ACTUAL.matcher(op); var never=NEVER.matcher(op);
            Metric rows=absent("ROWS","PER_LOOP_AVERAGE","rows"),time=absent("MILLISECONDS","PER_LOOP_AVERAGE","actual time end"),loops=absent("LOOPS","TOTAL","loops");
            if(actual.find()) {
                rows=value(actual.group(3),"ROWS","PER_LOOP_AVERAGE","rows");
                time=value(actual.group(2),"MILLISECONDS","PER_LOOP_AVERAGE","actual time end");
                loops=value(actual.group(4),"LOOPS","TOTAL","loops");
                Metric first=value(actual.group(1),"MILLISECONDS","PER_LOOP_AVERAGE","actual time start");
                if(!first.available() || (time.available() && first.decimal().compareTo(time.decimal())>0))
                    issues.add(new PlanCapture.Issue("INVALID_METRIC_VALUE",path,"Invalid first/last row time"));
                op=op.substring(0,actual.start());
                if(loops.available() && loops.decimal().signum()==0) { rows=notExecuted("ROWS","rows"); time=notExecuted("MILLISECONDS","actual time end"); }
            } else if(never.find()) {
                rows=notExecuted("ROWS","rows"); time=notExecuted("MILLISECONDS","actual time end");
                loops=Metric.absent("NOT_EXECUTED","LOOPS","TOTAL","loops","NODE_NOT_EXECUTED");
                op=op.substring(0,never.start());
            } else if(op.contains("(actual time=") || op.contains("(never executed"))
                issues.add(new PlanCapture.Issue("INVALID_METRIC_VALUE",path,"Unrecognized runtime metrics"));
            var cost=COST.matcher(op); Metric estimate=absent("ROWS","PER_INVOCATION","estimated rows"),total=absent("COST","NODE_ESTIMATE","cost"),startup=absent("COST","NODE_ESTIMATE","startup cost");
            if(cost.find()) {
                estimate=value(cost.group(3),"ROWS","PER_INVOCATION","estimated rows");
                total=value(cost.group(2)==null?cost.group(1):cost.group(2),"COST","NODE_ESTIMATE","cost");
                if(cost.group(2)!=null) startup=value(cost.group(1),"COST","NODE_ESTIMATE","startup cost");
                op=op.substring(0,cost.start());
            } else if(op.contains("(cost=")) issues.add(new PlanCapture.Issue("INVALID_METRIC_VALUE",path,"Unrecognized cost metrics"));
            var metrics=new NodeMetrics(1,"DATABASE_NODE",path,estimate,rows,time,loops,startup,total);
            for(var metric:List.of(estimate,rows,time,loops,startup,total)) if("PARSE_ERROR".equals(metric.state()))
                issues.add(new PlanCapture.Issue("INVALID_METRIC_VALUE",path,"Invalid metric: "+metric.sourceField()));
            Entry e=new Entry(indent,path,op.strip(),metrics,new ArrayList<>());
            if(stack.isEmpty()) roots.add(e); else stack.peek().children().add(e);
            stack.push(e);
        }
        if(roots.isEmpty()) return failed("PLAN_PARSE_FAILED");
        if(roots.size()>1 && count>=MAX_NODES)return failed("PLAN_NODE_LIMIT_EXCEEDED");
        PlanNode plan=roots.size()==1?node(roots.get(0)):new PlanNode("MySQL plan branches",0,0,0,0,0,roots.stream().map(MysqlTreePlanParser::node).toList(),null,ExecutionResult.PlanNodeConditions.EMPTY,
            new NodeMetrics(1,"APPLICATION_SYNTHETIC","tree",unknown("ROWS"),unknown("ROWS"),unknown("MILLISECONDS"),unknown("LOOPS"),unknown("COST"),unknown("COST")));
        return new ExplainService.Parsed(plan,issues.isEmpty()?"COMPLETE":"PARTIAL",issues);
    }
    private static PlanNode node(Entry e) { return new PlanNode(e.operation(),0,0,0,0,0,e.children().stream().map(MysqlTreePlanParser::node).toList(),null,ExecutionResult.PlanNodeConditions.EMPTY,e.metrics()); }
    public static Metric firstRow(String rawLine) {
        if(rawLine==null) return absent("MILLISECONDS","PER_LOOP_AVERAGE","actual time start");
        var m=ACTUAL.matcher(rawLine);
        return m.find()?(new BigDecimal(m.group(4)).signum()==0?notExecuted("MILLISECONDS","actual time start"):value(m.group(1),"MILLISECONDS","PER_LOOP_AVERAGE","actual time start")):
            NEVER.matcher(rawLine).find()?notExecuted("MILLISECONDS","actual time start"):absent("MILLISECONDS","PER_LOOP_AVERAGE","actual time start");
    }
    private static Metric value(String text,String unit,String basis,String field) {
        return Metric.exact(text,unit,basis,field);
    }
    private static Metric absent(String unit,String basis,String field) { return Metric.absent("NOT_COLLECTED",unit,basis,field,"METRIC_NOT_PROVIDED"); }
    private static Metric notExecuted(String unit,String field) { return Metric.absent("NOT_EXECUTED",unit,"PER_LOOP_AVERAGE",field,"NODE_NOT_EXECUTED"); }
    private static Metric unknown(String unit) { return Metric.absent("UNKNOWN",unit,"UNKNOWN",null,"SYNTHETIC_NODE"); }
    private static ExplainService.Parsed failed(String reason) { return new ExplainService.Parsed(null,"FAILED",List.of(new PlanCapture.Issue(reason,null,"TREE plan unavailable: "+reason))); }
}
