package com.bitdata.sqlworkbench.diagnostics.trace;

import com.bitdata.sqlworkbench.sql.*;
import com.bitdata.sqlworkbench.sql.model.FragmentNode;
import org.apache.calcite.sql.*;
import org.apache.calcite.sql.parser.SqlParser;
import java.math.BigInteger;
import java.util.*;

/** Strict, deliberately limited structural comparisons; never a lineage or causality proof. */
public final class CardinalityStructureInspector {
    public record Analysis(List<QuantityEvidence.Comparison> comparisons, List<TraceLimitation> limitations) {}
    private final SqlDialectResolver resolver;
    public CardinalityStructureInspector(SqlDialectResolver resolver) { this.resolver=resolver; }

    public Analysis inspect(TraceInputValidator.Scope scope, List<TraceStep> steps, List<KeyValue> keys, String dialect) {
        return inspectVerified(scope,steps,keys,dialect,(source,target,rule,ordinal)->false);
    }
    public Analysis inspect(TraceInputValidator.Scope scope,List<TraceStep> steps,List<KeyValue> keys,String dialect,
                            TransformVerifier.Verified verified) {
        return inspectVerified(scope,steps,keys,dialect,verified::allows);
    }
    @FunctionalInterface private interface Authorization {
        boolean allows(String source,String target,String rule,int ordinal);
    }
    private Analysis inspectVerified(TraceInputValidator.Scope scope,List<TraceStep> steps,List<KeyValue> keys,String dialect,
                                     Authorization comparable) {
        var profile=resolver.getProfile(dialect);
        Map<String,SqlNode> parsed=new HashMap<>();
        Map<String,TraceStep> observed=new HashMap<>(); steps.forEach(s -> { if(s.count()!=null) observed.put(s.fragmentId(),s); });
        List<TraceLimitation> limits=new ArrayList<>(); List<QuantityEvidence.Comparison> comparisons=new ArrayList<>();
        limits.add(limit("MULTI_STATEMENT_SNAPSHOT_UNVERIFIED",null,"Counts use separate statements without a guaranteed shared snapshot."));
        for(FragmentNode node:scope.nodes().values()) {
            try { parsed.put(node.id(),SqlParser.create(node.sqlFragment(),resolver.parserConfig(dialect)).parseQuery()); }
            catch(Exception failure) { limits.add(limit("STRUCTURE_UNVERIFIED",node.id(),"Strict dialect parsing failed; count facts remain available.")); }
        }
        for(FragmentNode parent:scope.nodes().values()) {
            if(!observed.containsKey(parent.id())) continue;
            SqlNode ast=parsed.get(parent.id());
            var children=scope.children().get(parent.id()).stream().map(scope.nodes()::get).toList();
            if(isUnionAll(ast)) {
                List<SqlNode> branches=new ArrayList<>(); flatten(ast,branches);
                List<QuantityEvidence.Observation> positions=new ArrayList<>(); Set<String> used=new HashSet<>();
                boolean complete=true; int positive=0;
                for(int i=0;i<branches.size();i++) {
                    String text=NodeToSql.toSql(branches.get(i),profile);
                    int ordinal=i+1;
                    FragmentNode match=children.stream().filter(c -> comparable.allows(parent.id(),c.id(),"set-op-split",ordinal) && observed.containsKey(c.id()) &&
                        parsed.containsKey(c.id()) && text.equals(NodeToSql.toSql(parsed.get(c.id()),profile))).findFirst().orElse(null);
                    if(match==null) { complete=false; break; }
                    positions.add(new QuantityEvidence.Observation(match.id(),i+1,!used.add(match.id())));
                    if(!observed.get(match.id()).count().equals("0")) positive++;
                }
                if(!complete) limits.add(limit("BRANCH_COVERAGE_INCOMPLETE",parent.id(),"Not all UNION ALL positions match submitted successful observations."));
                else if(positive>=2) comparisons.add(new QuantityEvidence.Comparison("UNION_ALL_BRANCHES",parent.id(),List.copyOf(positions),
                    List.of(limit("MULTI_STATEMENT_SNAPSHOT_UNVERIFIED",parent.id(),"Branch positions may reuse one observation; these are not independent measurements."))));
            } else if(ast instanceof SqlSelect select && select.getFrom() instanceof SqlJoin && !hasNestedQuery(select)) {
                Map<String,String> origins=keyOrigins(select,keys,dialect,profile);
                Map<String,String> leaves=leaves(select.getFrom(),profile,dialect);
                if(origins==null || leaves==null) { limits.add(limit("KEY_ORIGIN_UNVERIFIED",parent.id(),"Direct key projection and independent source could not be verified.")); continue; }
                Set<String> seen=new HashSet<>();
                for(FragmentNode child:children) {
                    if(!comparable.allows(parent.id(),child.id(),"join-split",0) || !seen.add(child.id()) || !observed.containsKey(child.id())) continue;
                    if(!(parsed.get(child.id()) instanceof SqlSelect childSelect) || hasNestedQuery(childSelect)) continue;
                    var childOrigins=keyOrigins(childSelect,keys,dialect,profile); var childLeaves=leaves(childSelect.getFrom(),profile,dialect);
                    if(childOrigins==null || childLeaves==null || !origins.equals(childOrigins)) continue;
                    String alias=origins.values().iterator().next().split("\\.",2)[0];
                    if(!Objects.equals(leaves.get(alias),childLeaves.get(alias)) || leaves.get(alias)==null) continue;
                    if(new BigInteger(observed.get(parent.id()).count()).compareTo(new BigInteger(observed.get(child.id()).count()))>0)
                        comparisons.add(new QuantityEvidence.Comparison("JOIN_COUNT_CHANGE",parent.id(),List.of(new QuantityEvidence.Observation(child.id(),null,false)),
                            List.of(limit("COLLATERAL_TRANSFORM_UNVERIFIED",parent.id(),"Column and predicate pruning can change more than one clause; this is not proof of cause."),
                                limit("MULTI_STATEMENT_SNAPSHOT_UNVERIFIED",parent.id(),"Counts were observed in separate statements."))));
                }
            } else if(ast!=null) limits.add(limit("STRUCTURE_UNVERIFIED",parent.id(),"This SQL structure has count facts only; automatic comparisons are limited."));
        }
        return new Analysis(List.copyOf(comparisons),List.copyOf(limits));
    }
    private static TraceLimitation limit(String code,String id,String message) { return new TraceLimitation(code,id,null,message); }
    private static boolean isUnionAll(SqlNode node) { return node instanceof SqlBasicCall call && call.getOperator().getName().equalsIgnoreCase("UNION ALL"); }
    private static void flatten(SqlNode node,List<SqlNode> branches) {
        if(isUnionAll(node)) for(SqlNode operand:((SqlCall)node).getOperandList()) flatten(operand,branches); else branches.add(node);
    }
    private static boolean containsQuery(SqlNode node) {
        if(node==null) return false;
        if(node instanceof SqlSelect || node instanceof SqlWith || node instanceof SqlOrderBy) return true;
        if(node instanceof SqlCall call) return call.getOperandList().stream().anyMatch(CardinalityStructureInspector::containsQuery);
        if(node instanceof SqlNodeList list) return list.getList().stream().anyMatch(CardinalityStructureInspector::containsQuery);
        return false;
    }
    private static boolean hasNestedQuery(SqlSelect select) {
        return containsQuery(select.getSelectList()) || containsQuery(select.getWhere()) || containsQuery(select.getHaving());
    }
    private static String name(SqlIdentifier id,int i,String dialect) {
        String text=id.names.get(i);
        return dialect.equals("postgresql") && !id.isComponentQuoted(i) ? text.toLowerCase(Locale.ROOT) : text;
    }
    private static String matchName(String name,DialectProfile profile) { return profile.caseSensitive()?name:name.toLowerCase(Locale.ROOT); }
    private static Map<String,String> keyOrigins(SqlSelect select,List<KeyValue> keys,String dialect,DialectProfile profile) {
        Map<String,String> all=new HashMap<>(); Set<String> duplicates=new HashSet<>();
        for(SqlNode item:select.getSelectList()) {
            SqlNode expression=item; String output=null;
            if(item instanceof SqlCall call && call.getKind()==SqlKind.AS) {
                expression=call.operand(0);
                if(call.operand(1) instanceof SqlIdentifier alias) output=name(alias,alias.names.size()-1,dialect);
            }
            if(expression instanceof SqlIdentifier id && !id.isStar() && id.names.size()==2) {
                if(output==null) output=name(id,1,dialect);
                String label=matchName(output,profile);
                if(all.put(label,name(id,0,dialect)+"."+name(id,1,dialect))!=null) duplicates.add(label);
            }
        }
        Map<String,String> result=new LinkedHashMap<>(); String source=null;
        for(KeyValue key:keys) {
            String bare=matchName(TraceQueryBuilder.bareColumnName(key.column()),profile), origin=all.get(bare);
            if(origin==null || duplicates.contains(bare)) return null;
            String alias=origin.split("\\.",2)[0]; if(source!=null && !source.equals(alias)) return null;
            source=alias; result.put(bare,origin);
        }
        return result;
    }
    private static Map<String,String> leaves(SqlNode from,DialectProfile profile,String dialect) {
        Map<String,String> result=new HashMap<>(); return collectLeaves(from,profile,dialect,result)?result:null;
    }
    private static boolean collectLeaves(SqlNode from,DialectProfile profile,String dialect,Map<String,String> result) {
        if(from instanceof SqlJoin join) return collectLeaves(join.getLeft(),profile,dialect,result) && collectLeaves(join.getRight(),profile,dialect,result);
        SqlNode relation=from; SqlIdentifier alias=null;
        if(from instanceof SqlCall call && call.getKind()==SqlKind.AS) { relation=call.operand(0); if(call.operand(1) instanceof SqlIdentifier id) alias=id; }
        if(!(relation instanceof SqlIdentifier) && !literalRelation(relation)) return false;
        if(alias==null && relation instanceof SqlIdentifier id) alias=id.getComponent(id.names.size()-1);
        if(alias==null) return false;
        // Same strict AST serialization preserves quoting and relation identity; never infer equivalence from ids.
        String key=name(alias,alias.names.size()-1,dialect);
        return result.putIfAbsent(key,NodeToSql.toSql(relation,profile))==null;
    }
    private static boolean literalRelation(SqlNode node) {
        if(isUnionAll(node)) return ((SqlCall)node).getOperandList().stream().allMatch(CardinalityStructureInspector::literalRelation);
        if(!(node instanceof SqlSelect select) || select.getFrom()!=null || select.getWhere()!=null || select.getHaving()!=null) return false;
        return select.getSelectList().getList().stream().allMatch(item -> {
            SqlNode expression=item instanceof SqlCall call && call.getKind()==SqlKind.AS ? call.operand(0):item;
            return expression instanceof SqlLiteral;
        });
    }
}
