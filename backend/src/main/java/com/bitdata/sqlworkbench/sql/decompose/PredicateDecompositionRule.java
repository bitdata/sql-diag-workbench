package com.bitdata.sqlworkbench.sql.decompose;

import com.bitdata.sqlworkbench.sql.*;
import com.bitdata.sqlworkbench.sql.model.FragmentType;
import com.bitdata.sqlworkbench.sql.model.DecompositionResult.*;
import org.apache.calcite.sql.*;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class PredicateDecompositionRule implements DecompositionRule {
    private final SqlDialectResolver resolver;
    public PredicateDecompositionRule(SqlDialectResolver resolver) { this.resolver=resolver; }
    public String name() { return "predicate-decomposition"; }
    public String displayName() { return "Isolate Predicate"; }
    public String description() { return "Keep one top-level WHERE operand"; }
    public DecompositionLevel level() { return DecompositionLevel.PREDICATE; }
    public List<DecomposedFragment> apply(SqlNode root, String dialect) {
        DialectProfile p=resolver.getProfile(dialect);
        return QueryScopeEdits.map(root,"query",scope -> {
            List<QueryScopeEdits.Edit> edits=new ArrayList<>();
            if (!(scope.node() instanceof SqlSelect s) || !(s.getWhere() instanceof SqlCall call)
                || (call.getKind()!=SqlKind.AND && call.getKind()!=SqlKind.OR) || call.operandCount()<2) return edits;
            for (SqlNode predicate:call.getOperandList()) {
                String text=NodeToSql.toSql(predicate,p);
                edits.add(new QueryScopeEdits.Edit(QueryScopeEdits.select(s,predicate,s.getHaving(),s.getOrderList(),s.getOffset(),s.getFetch()),
                    "Keep only: "+(text.length()>50?text.substring(0,50)+"...":text),
                    new Change("KEEP_ONLY","WHERE",scope.path(),NodeToSql.toSql(s.getWhere(),p),text)));
            }
            return edits;
        }).stream().map(e -> new DecomposedFragment(FragmentType.PREDICATE_DECOMPOSED,e.label(),NodeToSql.toSql(e.node(),p),name(),true,null,List.of(),
            new Effect(null,null,"PRESERVED",List.of(e.change()),List.of(),List.of()))).toList();
    }
}
