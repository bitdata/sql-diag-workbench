package com.bitdata.sqlworkbench.diagnostics.trace;

import com.bitdata.sqlworkbench.sql.model.FragmentNode;

import java.util.*;

/**
 * Identifies the elimination point in a trace: the deepest node where
 * a child has rows but the parent does not.
 */
public class EliminationDetector {

    /**
     * Root presence takes precedence; otherwise select an observed edge,
     * an execution error, or an inconclusive result. No source absence is inferred.
     *
     * <p>Parent-child relationships are inferred from the node map's children
     * field (not parentIds), so this works regardless of how nodes were
     * constructed.
     */
    public EliminationOutcome findEliminationOutcome(
            List<TraceStep> steps, Map<String, FragmentNode> nodes) {
        Set<String> children = new HashSet<>();
        nodes.values().forEach(n -> { if (n.children() != null) n.children().forEach(c -> children.add(c.id())); });
        String rootId = nodes.keySet().stream().filter(id -> !children.contains(id)).findFirst().orElse(null);
        return findEliminationOutcome(steps, nodes, rootId);
    }

    public EliminationOutcome findEliminationOutcome(List<TraceStep> steps, Map<String, FragmentNode> nodes, String rootId) {
        return findEliminationOutcome(steps,nodes,rootId,(source,target)->true);
    }
    public EliminationOutcome findEliminationOutcome(List<TraceStep> steps,Map<String,FragmentNode> nodes,String rootId,
            java.util.function.BiPredicate<String,String> comparable) {
        if (steps.isEmpty()) return new EliminationOutcome.Inconclusive("No successful observations in the current tree.");
        if (steps.stream().anyMatch(s -> Objects.equals(rootId, s.fragmentId()) && s.status() == TraceStatus.HAS_ROWS))
            return new EliminationOutcome.None();

        List<EliminationOutcome.Edge> candidates=candidateEdges(steps,nodes,comparable);
        if(!candidates.isEmpty()) return candidates.get(0);

        // 2. Error / TIMEOUT → cannot determine the elimination edge (K5)
        for (TraceStep step : steps) {
            if (step.status() == TraceStatus.ERROR
                || step.status() == TraceStatus.TIMEOUT) {
                String reason = (step.error() != null && !step.error().isBlank())
                    ? step.error() : step.status().name();
                return new EliminationOutcome.Error(step, reason);
            }
        }

        return new EliminationOutcome.Inconclusive("Current-tree observations do not locate a disappearance. Source data has not been verified.");
    }
    /** Unique pairs ordered by child observation order, then Java natural parent ID order. */
    public List<EliminationOutcome.Edge> candidateEdges(List<TraceStep> steps,Map<String,FragmentNode> nodes,
            java.util.function.BiPredicate<String,String> comparable) {
        Map<String,TraceStep> observed=new HashMap<>();
        steps.forEach(step -> observed.put(step.fragmentId(),step));
        Map<String,Set<String>> parents=new HashMap<>();
        nodes.forEach((id,node) -> {
            if(node.children()!=null) for(FragmentNode child:node.children())
                parents.computeIfAbsent(child.id(),ignored -> new TreeSet<>()).add(id);
        });
        List<EliminationOutcome.Edge> result=new ArrayList<>(); Set<Map.Entry<String,String>> seen=new HashSet<>();
        for(TraceStep child:steps) if(child.status()==TraceStatus.HAS_ROWS)
            for(String id:parents.getOrDefault(child.fragmentId(),Set.of())) {
                TraceStep parent=observed.get(id);
                if(parent!=null && parent.status()==TraceStatus.NO_ROWS && comparable.test(id,child.fragmentId())
                    && seen.add(Map.entry(id,child.fragmentId()))) result.add(new EliminationOutcome.Edge(child,parent));
            }
        return List.copyOf(result);
    }

}
