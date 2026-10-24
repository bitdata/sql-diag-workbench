package com.bitdata.sqlworkbench.sql.decompose;

import org.apache.calcite.sql.*;
import org.apache.calcite.util.Litmus;
import java.util.*;

/** Schema-free checks for the scope changed by JOIN removal, not a catalog validator. */
final class JoinCandidateSafety {
    private final String dialect;
    JoinCandidateSafety(String dialect) { this.dialect=dialect; }

    String key(SqlIdentifier id,int index) {
        String value=id.names.get(index);
        boolean quoted=id.isComponentQuoted(index)||id.names.size()==1&&id.getParserPosition().isQuoted();
        return "mysql".equalsIgnoreCase(dialect)||!quoted?value.toLowerCase(Locale.ROOT):value;
    }
    String alias(SqlNode n) {
        if(n instanceof SqlCall c&&c.getKind()==SqlKind.AS&&c.operand(1) instanceof SqlIdentifier id)return key(id,id.names.size()-1);
        if(n instanceof SqlIdentifier id)return key(id,id.names.size()-1);
        return n.toString();
    }
    private Set<String> aliases(SqlNode from) {
        Set<String> result=new LinkedHashSet<>();
        if(from instanceof SqlJoin j){result.addAll(aliases(j.getLeft()));result.addAll(aliases(j.getRight()));}
        else if(from!=null)result.add(alias(from));
        return result;
    }
    private Set<String> outputAliases(SqlNodeList list) {
        Set<String> result=new HashSet<>();
        if(list!=null)for(SqlNode n:list)if(n instanceof SqlCall c&&c.getKind()==SqlKind.AS&&c.operand(1) instanceof SqlIdentifier id)result.add(key(id,0));
        return result;
    }
    Set<String> references(SqlNode node,Set<String> known) {
        Facts facts=new Facts();walk(node,known,Set.of(),Set.of(),Set.of(),facts);return facts.references;
    }
    private static final class Facts {
        final Set<String> references=new LinkedHashSet<>();boolean unknown;
    }
    private Facts facts(SqlNode n,Set<String> known,Set<String> allowed,Set<String> windows) {
        Facts f=new Facts();walk(n,known,Set.of(),allowed,windows,f);return f;
    }
    private void walk(SqlNode n,Set<String> known,Set<String> shadowed,Set<String> allowed,Set<String> windows,Facts facts) {
        if(n==null)return;
        if(n instanceof SqlIdentifier id) {
            if(id.names.size()==1){if(!id.isStar()&&!allowed.contains(key(id,0)))facts.unknown=true;return;}
            if(id.names.size()!=2){facts.unknown=true;return;}
            String owner=key(id,0);
            if(!shadowed.contains(owner)){if(known.contains(owner))facts.references.add(owner);else facts.unknown=true;}
            return;
        }
        if(n instanceof SqlSelect s) {
            Set<String> own=new HashSet<>(shadowed);own.addAll(aliases(s.getFrom()));
            Set<String> names=outputAliases(s.getSelectList()),win=windowNames(s.getWindowList());
            walk(s.getSelectList(),known,own,Set.of(),win,facts);from(s.getFrom(),known,own,win,facts);
            walk(s.getWhere(),known,own,Set.of(),win,facts);walk(s.getGroup(),known,own,names,win,facts);
            walk(s.getHaving(),known,own,"mysql".equalsIgnoreCase(dialect)?names:Set.of(),win,facts);
            walk(s.getWindowList(),known,own,Set.of(),win,facts);walk(s.getQualify(),known,own,names,win,facts);
            walk(s.getOrderList(),known,own,names,win,facts);walk(s.getOffset(),known,own,Set.of(),win,facts);walk(s.getFetch(),known,own,Set.of(),win,facts);return;
        }
        if(n instanceof SqlOrderBy o){
            walk(o.query,known,shadowed,allowed,windows,facts);
            Set<String> own=new HashSet<>(shadowed);Set<String> names=Set.of();
            if(o.query instanceof SqlSelect s){own.addAll(aliases(s.getFrom()));names=outputAliases(s.getSelectList());}
            walk(o.orderList,known,own,names,windows,facts);walk(o.offset,known,own,Set.of(),windows,facts);walk(o.fetch,known,own,Set.of(),windows,facts);return;
        }
        if(n instanceof SqlWith){facts.unknown=true;return;}
        if(n instanceof SqlWindow w){
            if(w.getRefName()!=null&&!windows.contains(key(w.getRefName(),0)))facts.unknown=true;
            walk(w.getPartitionList(),known,shadowed,Set.of(),windows,facts);walk(w.getOrderList(),known,shadowed,Set.of(),windows,facts);
            walk(w.getLowerBound(),known,shadowed,Set.of(),windows,facts);walk(w.getUpperBound(),known,shadowed,Set.of(),windows,facts);return;
        }
        if(n instanceof SqlNodeList list){for(SqlNode item:list)walk(item,known,shadowed,allowed,windows,facts);return;}
        if(n instanceof SqlCall c){
            if(c.getKind()==SqlKind.AS||c.getKind()==SqlKind.CAST){walk(c.operand(0),known,shadowed,allowed,windows,facts);return;}
            if(c.getKind()==SqlKind.OVER){
                walk(c.operand(0),known,shadowed,allowed,windows,facts);
                SqlNode w=c.operand(1);
                if(w instanceof SqlIdentifier id){if(!windows.contains(key(id,0)))facts.unknown=true;}
                else walk(w,known,shadowed,Set.of(),windows,facts);
                return;
            }
            for(SqlNode item:c.getOperandList())walk(item,known,shadowed,allowed,windows,facts);
        }
    }
    private void from(SqlNode n,Set<String> known,Set<String> own,Set<String> windows,Facts facts){
        if(n==null||n instanceof SqlIdentifier)return;
        if(n instanceof SqlJoin j){from(j.getLeft(),known,own,windows,facts);from(j.getRight(),known,own,windows,facts);walk(j.getCondition(),known,own,Set.of(),windows,facts);}
        else if(n instanceof SqlCall c&&c.getKind()==SqlKind.AS)from(c.operand(0),known,own,windows,facts);
        else walk(n,known,own,Set.of(),windows,facts);
    }
    private Set<String> windowNames(SqlNodeList list){
        Set<String> result=new HashSet<>();if(list!=null)for(SqlNode n:list)if(n instanceof SqlWindow w&&w.getDeclName()!=null)result.add(key(w.getDeclName(),0));return result;
    }
    String problem(SqlSelect original,SqlSelect candidate,SqlNodeList outerOrder,Set<String> known,Set<String> remaining){
        if(candidate.getGroup()!=null||aggregate(candidate.getSelectList())||aggregate(candidate.getHaving())) {
            for(SqlNode item:candidate.getSelectList())if(item instanceof SqlIdentifier id&&id.isStar())
                return "A grouped or aggregate star projection requires schema knowledge; empty pruning cannot safely fall back to star";
        }
        Set<String> names=outputAliases(candidate.getSelectList()),windows=windowNames(candidate.getWindowList());
        List<Facts> scopes=List.of(facts(candidate.getSelectList(),known,Set.of(),windows),facts(candidate.getWhere(),known,Set.of(),windows),
            facts(candidate.getGroup(),known,names,windows),facts(candidate.getHaving(),known,"mysql".equalsIgnoreCase(dialect)?names:Set.of(),windows),
            facts(candidate.getWindowList(),known,Set.of(),windows),facts(candidate.getQualify(),known,names,windows),
            facts(candidate.getOrderList(),known,names,windows),facts(outerOrder,known,names,windows),
            facts(candidate.getOffset(),known,Set.of(),windows),facts(candidate.getFetch(),known,Set.of(),windows));
        for(Facts f:scopes){
            if(!remaining.containsAll(f.references))return "Candidate retains a reference to a removed relation (including HAVING/window/output dependencies)";
            if(f.unknown)return "Column, output alias or nested scope ownership cannot be proven without a schema";
        }
        Facts on=new Facts();checkFrom(candidate.getFrom(),known,windows,on);
        if(on.unknown||!remaining.containsAll(on.references))return "Remaining JOIN or derived query has an unresolved dependency";
        if(!ordinalsSafe(candidate.getGroup(),original.getSelectList(),candidate.getSelectList())
            ||!ordinalsSafe(candidate.getOrderList(),original.getSelectList(),candidate.getSelectList())
            ||!ordinalsSafe(outerOrder,original.getSelectList(),candidate.getSelectList()))return "Projection pruning changes the expression addressed by GROUP/ORDER ordinal";
        return null;
    }
    private void checkFrom(SqlNode n,Set<String> known,Set<String> windows,Facts f){
        if(n instanceof SqlJoin j){checkFrom(j.getLeft(),known,windows,f);checkFrom(j.getRight(),known,windows,f);walk(j.getCondition(),known,Set.of(),Set.of(),windows,f);}
        else if(n instanceof SqlCall c&&c.getKind()==SqlKind.AS)checkFrom(c.operand(0),known,windows,f);
        else if(!(n instanceof SqlIdentifier))walk(n,known,Set.of(),Set.of(),windows,f);
    }
    private boolean ordinalsSafe(SqlNodeList clause,SqlNodeList before,SqlNodeList after){
        if(clause==null)return true;
        for(SqlNode item:clause){
            while(item instanceof SqlCall c&&(c.getKind()==SqlKind.DESCENDING||c.getKind()==SqlKind.NULLS_FIRST||c.getKind()==SqlKind.NULLS_LAST))item=c.operand(0);
            if(item instanceof SqlNumericLiteral number){
                int position;
                try{position=number.intValue(true);}catch(ArithmeticException e){return false;}
                if(position<1||position>before.size()||position>after.size()||!before.get(position-1).equalsDeep(after.get(position-1),Litmus.IGNORE))return false;
            }
        }
        return true;
    }
    static boolean aggregate(SqlNode n){
        if(n==null||n instanceof SqlSelect||n instanceof SqlWith||n instanceof SqlOrderBy)return false;
        if(n instanceof SqlNodeList list){for(SqlNode item:list)if(aggregate(item))return true;}
        else if(n instanceof SqlCall c){
            if(c.getKind()==SqlKind.OVER){SqlNode fn=c.operand(0);if(fn instanceof SqlCall f)for(SqlNode item:f.getOperandList())if(aggregate(item))return true;return false;}
            // Babel may leave even COUNT/SUM as unresolved functions. An unknown
            // function is not proof that adding a raw key preserves output grain.
            if(c.getOperator().isAggregator()||c.getOperator() instanceof SqlUnresolvedFunction)return true;
            for(SqlNode item:c.getOperandList())if(aggregate(item))return true;
        }
        return false;
    }
}
