package com.bitdata.sqlworkbench.diagnostics.trace;

import com.bitdata.sqlworkbench.sql.*;
import com.bitdata.sqlworkbench.sql.decompose.*;
import com.bitdata.sqlworkbench.sql.model.FragmentNode;
import com.bitdata.sqlworkbench.sql.model.DecompositionResult.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Replays actual rules with the connection dialect. Client declarations never grant comparability. */
public final class TransformVerifier {
    public record Edge(String source,String target) {}
    private record Replay(String source,String rule) {}
    public record Position(String source,String target,String rule,int ordinal) {}
    public record Verified(Set<Edge> comparable, Set<Position> positions, List<TraceLimitation> limitations) {
        public boolean allows(String source,String target) { return comparable.contains(new Edge(source,target)); }
        public boolean allows(String source,String target,String rule,int ordinal) {
            return positions.stream().anyMatch(p -> p.source().equals(source) && p.target().equals(target)
                && p.rule().equals(rule) && (ordinal==0 || p.ordinal()==ordinal));
        }
    }
    private final SqlDialectResolver resolver;
    public TransformVerifier(SqlDialectResolver resolver) { this.resolver=resolver; }
    public Verified verify(Map<String,FragmentNode> nodes,List<Output> submitted,String dialect,BooleanSupplier active) {
        DecompositionRuleEngine engine=new DecompositionRuleEngine(new ClauseStrippingRule(resolver),new SplitSetOpRule(resolver),
            new PredicateDecompositionRule(resolver),new CteExpansionRule(resolver),new JoinSplitRule(resolver),new SubqueryExtractionRule(resolver),resolver);
        SqlParseEngine parser=new SqlParseEngine(resolver);
        Set<Edge> allowed=new HashSet<>(); List<TraceLimitation> limits=new ArrayList<>();Map<Replay,Batch> replay=new HashMap<>();
        Set<Position> positions=new HashSet<>();
        for(FragmentNode parent:nodes.values()) for(FragmentNode child:parent.children()) {
            if(!active.getAsBoolean()) return new Verified(Set.copyOf(allowed),Set.copyOf(positions),List.copyOf(limits));
            List<Output> candidates=submitted.stream().filter(o->o!=null&&parent.id().equals(o.sourceNodeId())&&child.id().equals(o.targetNodeId())).toList();
            boolean verified=false;
            for(Output input:candidates) {
                if(!active.getAsBoolean()) return new Verified(Set.copyOf(allowed),Set.copyOf(positions),List.copyOf(limits));
                try {
                    if(input.ruleName()==null || input.effect()==null)continue;
                    Replay key=new Replay(parent.id(),input.ruleName());Batch batch=replay.get(key);
                    if(batch==null) {
                        batch=engine.decomposeDetailed(parser.parse(parent.sqlFragment(),dialect),parent.sqlFragment(),dialect,input.ruleName(),parent.id());
                        replay.put(key,batch);
                    }
                    for(Output actual:batch.outputs()) {
                        FragmentNode emitted=batch.children().stream().filter(n->n.id().equals(actual.targetNodeId())).findFirst().orElse(null);
                        if(actual.ordinal()==input.ordinal()&&actual.sourceNodeId().equals(input.sourceNodeId())&&actual.targetNodeId().equals(input.targetNodeId())
                            &&actual.effect().equals(input.effect())&&emitted!=null&&emitted.sqlFragment().equals(child.sqlFragment())
                            &&"PRESERVED".equals(actual.effect().context())&&actual.effect().limitations().isEmpty()) {
                            verified=true;
                            positions.add(new Position(parent.id(),child.id(),input.ruleName(),input.ordinal()));
                        }
                    }
                } catch(Exception failure) { limits.add(new TraceLimitation("TRANSFORM_UNVERIFIED",parent.id(),child.id(),"Rule replay failed: "+failure.getClass().getSimpleName())); }
            }
            if(verified)allowed.add(new Edge(parent.id(),child.id()));
            else limits.add(new TraceLimitation(candidates.isEmpty()?"LEGACY_TRANSFORM_UNKNOWN":"TRANSFORM_UNVERIFIED",parent.id(),child.id(),
                "Observation retained; this edge has no verified comparable experiment context."));
        }
        return new Verified(Set.copyOf(allowed),Set.copyOf(positions),List.copyOf(limits));
    }
}
