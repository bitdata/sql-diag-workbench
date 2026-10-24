package com.bitdata.sqlworkbench.sql.decompose;

import com.bitdata.sqlworkbench.fragment.FragmentIdentity;
import com.bitdata.sqlworkbench.sql.*;
import com.bitdata.sqlworkbench.sql.model.DecompositionResult.*;
import org.apache.calcite.sql.*;
import java.util.*;

/** Describes actual AST component changes, including collateral edits made by legacy rules. */
final class ExperimentEffects {
    static Effect describe(SqlNode source, SqlNode target, String sourceSql, String targetSql,
                           DialectProfile profile, Effect declared) {
        Map<String,String> before=new LinkedHashMap<>(), after=new LinkedHashMap<>();
        inventory(source,"query",before,profile); inventory(target,"query",after,profile);
        List<Change> changes=new ArrayList<>(); List<Retained> retained=new ArrayList<>();
        if(declared!=null) changes.addAll(declared.changes());
        Set<String> keys=new LinkedHashSet<>(before.keySet());keys.addAll(after.keySet());
        for(String k:keys) {
            int split=k.lastIndexOf('/');String path=k.substring(0,split), subject=k.substring(split+1);
            String a=before.get(k),b=after.get(k);
            if(Objects.equals(a,b)) { if(a!=null)retained.add(new Retained(subject,path));continue; }
            if(changes.stream().noneMatch(c->c.scopePath().equals(path)&&c.subject().equals(subject)))
                changes.add(new Change(b==null?"REMOVE":a==null?"ADD":"KEEP_ONLY",subject,path,a,b));
        }
        return new Effect(FragmentIdentity.sha256Hex(sourceSql),FragmentIdentity.sha256Hex(targetSql),
            declared==null?"PRESERVED":declared.context(),List.copyOf(changes),List.copyOf(retained),declared==null?List.of():declared.limitations());
    }
    private static void put(Map<String,String> m,String path,String key,SqlNode n,DialectProfile p) {
        if(n!=null && (!(n instanceof SqlNodeList l)||!l.isEmpty()))m.put(path+"/"+key,NodeToSql.toSql(n,p));
    }
    private static void inventory(SqlNode n,String path,Map<String,String> m,DialectProfile p) {
        if(n instanceof SqlOrderBy o) {
            put(m,path,"ORDER_BY",o.orderList,p);put(m,path,"OFFSET",o.offset,p);put(m,path,"LIMIT",o.fetch,p);
            inventory(o.query,path+"/query",m,p);
        } else if(n instanceof SqlWith w) {
            for(int i=0;i<w.withList.size();i++) {
                SqlWithItem item=(SqlWithItem)w.withList.get(i);String q=path+"/cte["+i+"]";
                put(m,q,"CTE_NAME",item.name,p);put(m,q,"CTE_COLUMNS",item.columnList,p);inventory(item.query,q,m,p);
            }
            inventory(w.body,path+"/body",m,p);
        } else if(n instanceof SqlSelect s) {
            put(m,path,"SELECT",s.getSelectList(),p);put(m,path,"FROM_JOIN_ON",s.getFrom(),p);
            put(m,path,"WHERE",s.getWhere(),p);put(m,path,"GROUP_BY",s.getGroup(),p);put(m,path,"HAVING",s.getHaving(),p);
            put(m,path,"WINDOW",s.getWindowList(),p);put(m,path,"ORDER_BY",s.getOrderList(),p);
            put(m,path,"OFFSET",s.getOffset(),p);put(m,path,"LIMIT",s.getFetch(),p);
        } else if(n instanceof SqlCall c && n.isA(SqlKind.SET_QUERY)) {
            m.put(path+"/SET_OPERATOR",c.getOperator().getName());
            for(int i=0;i<c.operandCount();i++)inventory(c.operand(i),path+"/branch["+i+"]",m,p);
        } else put(m,path,"QUERY",n,p);
    }
}
