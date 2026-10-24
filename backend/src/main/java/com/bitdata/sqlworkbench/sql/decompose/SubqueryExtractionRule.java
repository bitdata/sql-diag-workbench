package com.bitdata.sqlworkbench.sql.decompose;

import com.bitdata.sqlworkbench.sql.*;
import com.bitdata.sqlworkbench.sql.model.FragmentType;
import com.bitdata.sqlworkbench.sql.model.DecompositionResult.*;
import org.apache.calcite.sql.*;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.springframework.stereotype.Component;
import java.util.*;

/** First-query-boundary discovery with conservative lexical dependency analysis. */
@Component
public class SubqueryExtractionRule implements DecompositionRule {
    private final SqlDialectResolver resolver;
    public SubqueryExtractionRule(SqlDialectResolver resolver) { this.resolver=resolver; }
    public String name() { return "subquery-extraction"; }
    public String displayName() { return "Extract Subquery"; }
    public String description() { return "Extract an independent experiment and report removed correlations"; }
    public List<DecomposedFragment> apply(SqlNode root,String dialect) { RuleEvaluation result=evaluate(root,dialect); if(!result.issues().isEmpty())throw new IllegalStateException(result.issues().toString()); return result.fragments(); }
    public RuleEvaluation evaluate(SqlNode root,String dialect) {
        List<DecomposedFragment> fragments=new ArrayList<>(); List<Issue> issues=new ArrayList<>();
        discoverScopes(root,"query",dialect,fragments,issues,null);
        return new RuleEvaluation(fragments,issues);
    }
    private void discoverScopes(SqlNode n,String path,String dialect,List<DecomposedFragment> out,List<Issue> issues,SqlWith enclosing) {
        if (n instanceof SqlOrderBy ob) { discoverScopes(ob.query,path,dialect,out,issues,enclosing); return; }
        if (n instanceof SqlWith w) {
            for(int i=0;i<w.withList.size();i++) discoverScopes(((SqlWithItem)w.withList.get(i)).query,path+"/cte["+i+"]",dialect,out,issues,w);
            discoverScopes(w.body,path+"/body",dialect,out,issues,w); return;
        }
        if (!(n instanceof SqlSelect s)) return;
        Set<String> outer=aliases(s.getFrom(),dialect);
        walk(s.getSelectList(),path+"/select",outer,dialect,out,issues,enclosing);
        walk(s.getFrom(),path+"/from",outer,dialect,out,issues,enclosing);
        walk(s.getWhere(),path+"/where",outer,dialect,out,issues,enclosing);
        walk(s.getHaving(),path+"/having",outer,dialect,out,issues,enclosing);
        walk(s.getGroup(),path+"/group",outer,dialect,out,issues,enclosing);
        walk(s.getWindowList(),path+"/window",outer,dialect,out,issues,enclosing);
    }
    private boolean query(SqlNode n) { return n instanceof SqlSelect || n instanceof SqlOrderBy || n instanceof SqlWith || n!=null && n.isA(SqlKind.SET_QUERY); }
    private void walk(SqlNode n,String path,Set<String> outer,String dialect,List<DecomposedFragment> out,List<Issue> issues,SqlWith enclosing) {
        if(n==null)return;
        if(query(n)) { extract(n,path,outer,dialect,out,issues,enclosing); return; }
        if(n instanceof SqlNodeList list) { for(int i=0;i<list.size();i++)walk(list.get(i),path+"["+i+"]",outer,dialect,out,issues,enclosing); }
        else if(n instanceof SqlCall call) { for(int i=0;i<call.operandCount();i++)walk(call.operand(i),path+"/"+call.getKind()+"/operand["+i+"]",outer,dialect,out,issues,enclosing); }
    }
    private void extract(SqlNode original,String path,Set<String> outer,String dialect,List<DecomposedFragment> out,List<Issue> issues,SqlWith enclosing) {
        DialectProfile p=resolver.getProfile(dialect);
        SqlNode inner=original instanceof SqlOrderBy ob?ob.query:original;
        String originalSql=NodeToSql.toSql(original,p);
        if(!(inner instanceof SqlSelect s)) {
            SqlNode closed=enclosing==null?original:closeCtes(original,enclosing,dialect);
            if(closed==null || refs(original,Set.of(),Set.of(),dialect)!=0) { issues.add(issue(path,originalSql,"Query scope cannot be independently verified")); return; }
            out.add(new DecomposedFragment(FragmentType.SUBQUERY_EXTRACTED,label(path),NodeToSql.toSql(closed,p),name(),true,"Independent subquery",List.of(),
                new Effect(null,null,"PRESERVED",List.of(new Change("EXTRACT","SUBQUERY",path,originalSql,NodeToSql.toSql(closed,p))),List.of(),List.of())));
            return;
        }
        Set<String> local=aliases(s.getFrom(),dialect);
        List<SqlNode> terms=new ArrayList<>(); splitAnd(s.getWhere(),terms);
        List<SqlNode> kept=new ArrayList<>(); List<Change> changes=new ArrayList<>();
        boolean unresolved=false;
        for(SqlNode term:terms) {
            int refs=refs(term,local,outer,dialect);
            if((refs&2)!=0) unresolved=true;
            else if((refs&1)!=0) changes.add(new Change("REMOVE","CORRELATED_PREDICATE",path+"/where",NodeToSql.toSql(term,p),null));
            else kept.add(term);
        }
        SqlNode where=null;
        for(SqlNode term:kept) where=where==null?term:SqlStdOperatorTable.AND.createCall(term.getParserPosition(),where,term);
        SqlSelect modified=QueryScopeEdits.select(s,where,s.getHaving(),s.getOrderList(),s.getOffset(),s.getFetch());
        SqlNode candidate=original instanceof SqlOrderBy ob?new SqlOrderBy(ob.getParserPosition(),modified,ob.orderList,ob.offset,ob.fetch):modified;
        // Checking the entire remaining query catches SELECT/HAVING/ON/GROUP/window/local ordering dependencies.
        if(refs(candidate,Set.of(),Set.of(),dialect)!=0) unresolved=true;
        if(enclosing!=null) {
            SqlNode closed=closeCtes(candidate,enclosing,dialect);
            if(closed==null) unresolved=true; else candidate=closed;
        }
        if(unresolved) { issues.add(issue(path,originalSql,"External or ambiguous column/CTE dependency remains; no runnable fragment emitted")); return; }
        changes.add(0,new Change("EXTRACT","SUBQUERY",path,originalSql,NodeToSql.toSql(candidate,p)));
        String context=changes.size()>1?"DETACHED":"PRESERVED";
        List<Issue> limitations=context.equals("DETACHED")?List.of(new Issue("DETACHED_CONTEXT","Independent experiment; aggregate values are not the original parent row's correlated values",name(),path,null,null)):List.of();
        String label=label(path);
        out.add(new DecomposedFragment(FragmentType.SUBQUERY_EXTRACTED,label,NodeToSql.toSql(candidate,p),name(),true,
            context.equals("DETACHED")?"Correlation removed — independent experiment":"Independent subquery",List.of(),new Effect(null,null,context,changes,List.of(),limitations)));
    }
    private String label(String path) { return path.contains("/select")?"SELECT list subquery":path.contains("/from")?"FROM derived subquery":path.contains("/having")?"HAVING subquery":path.contains("/EXISTS/")?"WHERE EXISTS subquery":path.contains("/IN/")?"WHERE IN subquery":"WHERE subquery"; }
    private Issue issue(String path,String sql,String message) { return new Issue("UNRESOLVED_DEPENDENCY",message,name(),path,null,sql); }
    private void splitAnd(SqlNode n,List<SqlNode> terms) {
        if(n==null)return;
        if(n instanceof SqlCall c && c.getKind()==SqlKind.AND) for(SqlNode o:c.getOperandList())splitAnd(o,terms); else terms.add(n);
    }
    // 0 = local/literal, 1 = proven outer, 2 = unknown. Inner scopes shadow outer names.
    private int refs(SqlNode n,Set<String> local,Set<String> outer,String dialect) {
        if(n==null)return 0;
        if(n instanceof SqlSelect s) {
            Set<String> own=new LinkedHashSet<>(local), parents=new LinkedHashSet<>(outer); own.addAll(aliases(s.getFrom(),dialect));
            int r=refs(s.getSelectList(),own,parents,dialect)|fromRefs(s.getFrom(),own,parents,dialect)
                |refs(s.getWhere(),own,parents,dialect)|refs(s.getGroup(),own,parents,dialect)|refs(s.getHaving(),own,parents,dialect)
                |refs(s.getWindowList(),own,parents,dialect)|refs(s.getOrderList(),own,parents,dialect)
                |refs(s.getOffset(),own,parents,dialect)|refs(s.getFetch(),own,parents,dialect);
            // Nested references to still-present parents are legal; references outside this query remain unknown.
            return r;
        }
        if(n instanceof SqlOrderBy ob) {
            SqlNode q=ob.query; Set<String> own=q instanceof SqlSelect s?aliases(s.getFrom(),dialect):local;
            return refs(q,local,outer,dialect)|refs(ob.orderList,own,outer,dialect)|refs(ob.offset,own,outer,dialect)|refs(ob.fetch,own,outer,dialect);
        }
        if(n instanceof SqlWith) return 2;
        if(n instanceof SqlIdentifier id) {
            if(id.isStar() && id.names.size()==1)return 0;
            if(id.names.size()<2)return 2; // schema-free bare-column ownership cannot be proven
            // A terminal table name cannot prove the catalog/schema binding.
            // Require a visible two-component alias instead of discarding prefixes.
            if(id.names.size()>2)return 2;
            String qualifier=key(id,id.names.size()-2,dialect);
            if(local.contains(qualifier))return 0;
            return outer.contains(qualifier)?1:2;
        }
        if(n instanceof SqlNodeList list) { int r=0; for(SqlNode v:list)r|=refs(v,local,outer,dialect); return r; }
        if(n instanceof SqlCall c) {
            // AS name and CAST type are declarations, not column references.
            if(c.getKind()==SqlKind.AS || c.getKind()==SqlKind.CAST) return refs(c.operand(0),local,outer,dialect);
            int r=0;for(SqlNode v:c.getOperandList())r|=refs(v,local,outer,dialect);return r;
        }
        return 0;
    }
    private int fromRefs(SqlNode n,Set<String> local,Set<String> outer,String dialect) {
        if(n==null || n instanceof SqlIdentifier)return 0;
        if(n instanceof SqlJoin j)return fromRefs(j.getLeft(),local,outer,dialect)|fromRefs(j.getRight(),local,outer,dialect)|refs(j.getCondition(),local,outer,dialect);
        if(n instanceof SqlCall c && c.getKind()==SqlKind.AS)return fromRefs(c.operand(0),local,outer,dialect);
        return refs(n,local,outer,dialect);
    }
    private String key(SqlIdentifier id,int i,String dialect) {
        String v=id.names.get(i);
        if("mysql".equalsIgnoreCase(dialect))return v.toLowerCase(Locale.ROOT);
        return id.isComponentQuoted(i) || id.names.size()==1 && id.getParserPosition().isQuoted()?v:v.toLowerCase(Locale.ROOT);
    }
    private Set<String> aliases(SqlNode n,String dialect) {
        Set<String> result=new LinkedHashSet<>(); aliases(n,result,dialect);return result;
    }
    private void aliases(SqlNode n,Set<String> out,String dialect) {
        if(n instanceof SqlJoin j) { aliases(j.getLeft(),out,dialect);aliases(j.getRight(),out,dialect); }
        else if(n instanceof SqlCall c && c.getKind()==SqlKind.AS && c.operand(1) instanceof SqlIdentifier id)out.add(key(id,id.names.size()-1,dialect));
        else if(n instanceof SqlIdentifier id)out.add(key(id,id.names.size()-1,dialect));
    }
    Set<String> collectTableAliases(SqlNode from) { return aliases(from,"postgresql"); }
    private SqlNode closeCtes(SqlNode candidate,SqlWith enclosing,String dialect) {
        // Preserve exact AST definitions and names; never uppercase quoted CTE identities.
        Map<String,SqlWithItem> definitions=new LinkedHashMap<>();
        for(SqlNode n:enclosing.withList) { SqlWithItem i=(SqlWithItem)n; String k=key(i.name,0,dialect);if(definitions.put(k,i)!=null)return null; }
        Set<String> needed=new LinkedHashSet<>(); collectTables(candidate,definitions.keySet(),needed,dialect);
        Set<String> done=new LinkedHashSet<>(),visiting=new HashSet<>();List<SqlNode> ordered=new ArrayList<>();
        for(String k:needed)if(!visitCte(k,definitions,done,visiting,ordered,dialect))return null;
        return ordered.isEmpty()?candidate:new SqlWith(enclosing.getParserPosition(),new SqlNodeList(ordered,enclosing.withList.getParserPosition()),candidate);
    }
    private boolean visitCte(String k,Map<String,SqlWithItem> defs,Set<String> done,Set<String> visiting,List<SqlNode> ordered,String dialect) {
        if(done.contains(k))return true;if(!visiting.add(k))return false;
        SqlWithItem item=defs.get(k); if(item.query instanceof SqlWith || refs(item.query,Set.of(),Set.of(),dialect)!=0)return false;
        Set<String> deps=new LinkedHashSet<>();collectTables(item.query,defs.keySet(),deps,dialect);
        for(String dep:deps)if(!visitCte(dep,defs,done,visiting,ordered,dialect))return false;
        visiting.remove(k);done.add(k);ordered.add(item);return true;
    }
    private void collectTables(SqlNode n,Set<String> names,Set<String> refs,String dialect) {
        if(n instanceof SqlSelect s) { collectFrom(s.getFrom(),names,refs,dialect); }
        if(n instanceof SqlNodeList l)for(SqlNode v:l)collectTables(v,names,refs,dialect);
        else if(n instanceof SqlCall c)for(SqlNode v:c.getOperandList())if(v!=null)collectTables(v,names,refs,dialect);
    }
    private void collectFrom(SqlNode n,Set<String> names,Set<String> refs,String dialect) {
        if(n instanceof SqlIdentifier id && id.names.size()==1) { String k=key(id,0,dialect);if(names.contains(k))refs.add(k); }
        else if(n instanceof SqlJoin j) {collectFrom(j.getLeft(),names,refs,dialect);collectFrom(j.getRight(),names,refs,dialect);}
        else if(n instanceof SqlCall c && c.getKind()==SqlKind.AS)collectFrom(c.operand(0),names,refs,dialect);
    }
}
