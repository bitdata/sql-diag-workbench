package com.bitdata.sqlworkbench.diagnostics.trace;

import com.bitdata.sqlworkbench.sql.model.FragmentNode;
import java.util.*;

/** Shared observation scope: identities, complete graph edges and serialized positions stay separate. */
public final class TraceInputValidator {
    public record Occurrence(String parentId, FragmentNode node, int ordinal) {}
    public record Scope(Map<String, FragmentNode> nodes, List<Occurrence> occurrences,
                        Map<String, Set<String>> children, List<String> leafFirst) {}
    public static final class Invalid extends IllegalArgumentException {
        public final String code;
        Invalid(String code, String message) { super(message); this.code=code; }
    }
    private record Frame(FragmentNode node, String parentId, int ordinal, Set<String> ancestors) {}

    public Scope validate(FragmentNode root) {
        if (root == null || root.parentIds() != null && !root.parentIds().isEmpty())
            throw new Invalid("TRACE_INPUT_INVALID", "A root with no parents is required");
        Map<String,FragmentNode> nodes=new LinkedHashMap<>();
        List<Occurrence> occurrences=new ArrayList<>();
        Map<String,Set<String>> edges=new LinkedHashMap<>();
        Deque<Frame> stack=new ArrayDeque<>(); stack.push(new Frame(root,null,0,Set.of()));
        while(!stack.isEmpty()) {
            Frame frame=stack.pop(); FragmentNode node=frame.node();
            if(node==null || node.id()==null || node.id().isBlank()) throw new Invalid("TRACE_INPUT_INVALID","Fragment identity is missing");
            if(frame.ancestors().contains(node.id())) throw new Invalid("TRACE_INPUT_CYCLE","Fragment ancestors contain a cycle");
            FragmentNode previous=nodes.putIfAbsent(node.id(),node);
            if(previous!=null && (!Objects.equals(previous.sqlFragment(),node.sqlFragment()) || previous.executable()!=node.executable()
                    || !Objects.equals(previous.canonicalSql(),node.canonicalSql()) || !Objects.equals(previous.canonicalizationError(),node.canonicalizationError())))
                throw new Invalid("TRACE_NODE_CONFLICT","The same fragment identity has conflicting SQL or identity metadata");
            occurrences.add(new Occurrence(frame.parentId(),copy(node,List.of()),frame.ordinal()));
            edges.computeIfAbsent(node.id(), ignored -> new TreeSet<>());
            if(frame.parentId()!=null) edges.get(frame.parentId()).add(node.id());
            if(nodes.size()>64 || occurrences.size()>512) throw new Invalid("TRACE_SCOPE_TOO_LARGE","Investigate a smaller workspace (64 unique fragments / 512 occurrences maximum)");
            Set<String> ancestors=new HashSet<>(frame.ancestors()); ancestors.add(node.id());
            List<FragmentNode> children=node.children()==null?List.of():node.children();
            for(int i=children.size()-1;i>=0;i--) stack.push(new Frame(children.get(i),node.id(),i+1,ancestors));
        }
        // Freeze a complete one-level projection for legacy graph consumers. No consumer
        // may derive adjacency from the first serialized copy of a shared identity.
        Map<String,FragmentNode> normalized=new LinkedHashMap<>();
        for(var entry:nodes.entrySet()) normalized.put(entry.getKey(),copy(entry.getValue(),
            edges.get(entry.getKey()).stream().map(nodes::get).map(n -> copy(n,List.of())).toList()));
        List<String> order;
        try { order=new DagWalker().topologicalSort(normalized); }
        catch(IllegalStateException cycle) { throw new Invalid("TRACE_INPUT_CYCLE","Merged fragment graph contains a cycle"); }
        Map<String,Set<String>> frozen=new LinkedHashMap<>();
        edges.forEach((id,children) -> frozen.put(id,Collections.unmodifiableSet(new TreeSet<>(children))));
        return new Scope(Collections.unmodifiableMap(normalized),List.copyOf(occurrences),
            Collections.unmodifiableMap(frozen),List.copyOf(order));
    }
    private static FragmentNode copy(FragmentNode n,List<FragmentNode> children) {
        return new FragmentNode(n.id(),n.type(),n.label(),n.sqlFragment(),n.executable(),n.executionHint(),children.size(),
            n.ruleName(),children,n.parentIds()==null?List.of():List.copyOf(n.parentIds()),n.transform(),n.source(),
            n.canonicalSql(),n.editParentId(),n.createdAt(),n.executionSummary(),n.canonicalizationError());
    }
}
