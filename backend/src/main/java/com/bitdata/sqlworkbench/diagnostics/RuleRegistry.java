package com.bitdata.sqlworkbench.diagnostics;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Discovers and registers all {@link DiagnosticRule} beans.
 * Rules are sorted by severity (HIGH → MEDIUM → LOW) for consistent ordering.
 */
@Component
public class RuleRegistry {

    private static final Logger log = LoggerFactory.getLogger(RuleRegistry.class);

    private final ApplicationContext ctx;
    private List<DiagnosticRule> rules = List.of();

    public RuleRegistry(ApplicationContext ctx) {
        this.ctx = ctx;
    }

    @PostConstruct
    public void init() {
        // Retrieve all DiagnosticRule beans from the application context
        this.rules = ctx.getBeansOfType(DiagnosticRule.class).values().stream()
            .toList();
        this.rules = new java.util.ArrayList<>(rules);
        this.rules.sort(Comparator.comparing(DiagnosticRule::severity));
        log.info("Registered {} diagnostic rules: {}",
            rules.size(),
            rules.stream().map(DiagnosticRule::id).toList());
    }

    public List<DiagnosticRule> getRules() {
        return List.copyOf(rules);
    }

    /** Run all rules against a fragment and collect findings. */
    public List<DiagnosticFinding> analyzeAll(
            com.bitdata.sqlworkbench.sql.model.FragmentNode fragment,
            com.bitdata.sqlworkbench.execution.model.ExecutionResult.PlanNode plan,
            com.bitdata.sqlworkbench.execution.model.ExecutionResult.Timing timing) {
        List<DiagnosticFinding> allFindings = new ArrayList<>();
        for (DiagnosticRule rule : rules) {
            try {
                List<DiagnosticFinding> findings = rule.analyze(fragment, plan, timing);
                if (findings != null) {
                    allFindings.addAll(findings);
                }
            } catch (Exception e) {
                log.warn("Rule {} threw during analysis: {}", rule.id(), e.getMessage());
                allFindings.add(failedRule(rule, fragment == null ? null : fragment.id()));
            }
        }
        return allFindings;
    }

    public record Evaluated(List<DiagnosticFinding> findings,Evaluation evaluation) {}
    public Evaluated evaluate(com.bitdata.sqlworkbench.sql.model.FragmentNode fragment,
            com.bitdata.sqlworkbench.execution.ExecutionEngine.PlanObservation observation,String executionId) {
        var findings=new ArrayList<DiagnosticFinding>();
        var coverage=new ArrayList<Evaluation.RuleEvaluation>();
        var plan=observation.result()==null?null:observation.result().plan();
        for(var rule:rules) {
            String reason=null; String status="EVALUATED";
            switch(rule.id()) {
                case "expected-row-missing-at-source" -> { status="NOT_APPLICABLE"; reason="TRACE_ONLY"; }
                case "expected-row-eliminated-at-filter", "expected-row-eliminated-at-join" -> { status="SKIPPED"; reason="PLAN_COUNTS_DO_NOT_PROVE_ROW_ELIMINATION"; }
                case "join-amplification" -> { status="SKIPPED"; reason="INCOMPARABLE_OPERATOR_COUNTS"; }
                case "non-sargable-predicate" -> { }
                default -> {
                    if(!"VERIFIED".equals(observation.sourceState())) { status="SKIPPED"; reason=observation.errorReason(); }
                    else if("estimated-vs-actual".equals(rule.id())) {
                        var nodes=PlanNodeWalker.findAll(plan,n -> n.metrics()!=null && "DATABASE_NODE".equals(n.metrics().origin()));
                        boolean any=nodes.stream().anyMatch(n -> comparable(n));
                        boolean all=!nodes.isEmpty() && nodes.stream().allMatch(n -> comparable(n));
                        if(!all) { status="SKIPPED"; reason=any?"PARTIAL_EVALUATION":"ACTUAL_METRIC_UNAVAILABLE"; }
                    } else if("sequential-scan".equals(rule.id()) && PlanNodeWalker.findAll(plan,n -> n.operation()!=null && n.operation().toLowerCase(java.util.Locale.ROOT).contains("seq scan")).isEmpty()) {
                        status="NOT_APPLICABLE"; reason="NO_APPLICABLE_NODE";
                    }
                }
            }
            try {
                if(status.equals("EVALUATED") || "PARTIAL_EVALUATION".equals(reason)) {
                    var result=rule.analyze(fragment,plan,observation.result()==null?null:observation.result().timing());
                    if(result!=null) findings.addAll(result);
                }
            } catch(Exception failure) { status="FAILED"; reason="RULE_FAILED"; findings.add(failedRule(rule,fragment.id())); }
            coverage.add(new Evaluation.RuleEvaluation(rule.id(),status,reason));
        }
        boolean degraded=!"VERIFIED".equals(observation.sourceState()) || coverage.stream().anyMatch(r -> r.status().equals("SKIPPED") || r.status().equals("FAILED"));
        return new Evaluated(List.copyOf(findings),new Evaluation(1,executionId,observation.sourceState(),coverage,degraded,
            observation.errorReason()!=null?observation.errorReason():degraded?"PARTIAL_EVALUATION":null));
    }
    private static boolean comparable(com.bitdata.sqlworkbench.execution.model.ExecutionResult.PlanNode n) {
        var m=n.metrics();
        return m!=null && m.estimatedRows().available() && m.actualRows().available() && m.loops().available() && m.loops().decimal().signum()>0;
    }

    /** Run all rules' trace analysis and collect findings. */
    public List<DiagnosticFinding> analyzeAllTrace(
            com.bitdata.sqlworkbench.diagnostics.trace.TraceContext context) {
        List<DiagnosticFinding> allFindings = new ArrayList<>();
        for (DiagnosticRule rule : rules) {
            try {
                List<DiagnosticFinding> findings = rule.analyzeTrace(context);
                if (findings != null) {
                    allFindings.addAll(findings);
                }
            } catch (Exception e) {
                log.warn("Rule {} threw during trace analysis: {}", rule.id(), e.getMessage());
                allFindings.add(failedRule(rule, context.rootFragment() == null ? null : context.rootFragment().id()));
            }
        }
        return allFindings;
    }

    private DiagnosticFinding failedRule(DiagnosticRule rule, String nodeId) {
        return DiagnosticFinding.builder().id("rule-failed-" + rule.id()).ruleId(rule.id())
            .title("Analysis incomplete: " + rule.displayName()).message("The diagnostic rule failed. Its analysis is unavailable.")
            .severity(DiagnosticRule.Severity.LOW).affectedNodeId(nodeId).confidence(0)
            .degraded(true).errorReason("RULE_FAILED").build();
    }
}
