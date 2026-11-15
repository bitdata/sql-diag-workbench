package com.bitdata.sqlworkbench.sql.decompose;

import com.bitdata.sqlworkbench.sql.*;
import com.bitdata.sqlworkbench.sql.model.FragmentType;
import com.bitdata.sqlworkbench.sql.model.DecompositionResult.*;
import org.apache.calcite.sql.*;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class ClauseStrippingRule implements DecompositionRule {
    private final SqlDialectResolver resolver;
    public ClauseStrippingRule(SqlDialectResolver resolver) { this.resolver = resolver; }
    public String name() { return "clause-stripping"; }
    public String displayName() { return "Strip Clause"; }
    public String description() { return "Remove one clause while retaining other clauses in its scope"; }
    public DecompositionLevel level() { return DecompositionLevel.CLAUSE; }
    public List<DecomposedFragment> apply(SqlNode root, String dialect) {
        RuleEvaluation result = evaluate(root, dialect);
        if (!result.issues().isEmpty()) throw new IllegalStateException(result.issues().toString());
        return result.fragments();
    }
    public RuleEvaluation evaluate(SqlNode root, String dialect) {
        DialectProfile p = resolver.getProfile(dialect);
        List<Issue> issues = new ArrayList<>();
        List<DecomposedFragment> fragments = new ArrayList<>();
        var candidates = QueryScopeEdits.map(root, "query", scope -> {
            SqlNode n = scope.node(); String path = scope.path();
            List<QueryScopeEdits.Edit> edits = new ArrayList<>();
            if (n instanceof SqlOrderBy ob) {
                if (ob.orderList != null && !ob.orderList.isEmpty())
                    edits.add(new QueryScopeEdits.Edit(new SqlOrderBy(ob.getParserPosition(), ob.query, SqlNodeList.EMPTY, ob.offset, ob.fetch),
                        "Remove ORDER BY", new Change("REMOVE", "ORDER_BY", path, NodeToSql.toSql(ob.orderList, p), null)));
                if (ob.offset != null || ob.fetch != null)
                    edits.add(new QueryScopeEdits.Edit(new SqlOrderBy(ob.getParserPosition(), ob.query, ob.orderList, null, null),
                        "Remove LIMIT/OFFSET", new Change("REMOVE", "PAGINATION", path, NodeToSql.toSql(ob, p), null)));
            } else if (n instanceof SqlSelect s) {
                if (s.getWhere() != null) edits.add(new QueryScopeEdits.Edit(QueryScopeEdits.select(s,null,s.getHaving(),s.getOrderList(),s.getOffset(),s.getFetch()),
                    "Remove WHERE", new Change("REMOVE","WHERE",path,NodeToSql.toSql(s.getWhere(),p),null)));
                if (s.getHaving() != null) edits.add(new QueryScopeEdits.Edit(QueryScopeEdits.select(s,s.getWhere(),null,s.getOrderList(),s.getOffset(),s.getFetch()),
                    "Remove HAVING", new Change("REMOVE","HAVING",path,NodeToSql.toSql(s.getHaving(),p),null)));
                if (s.getOrderList() != null && !s.getOrderList().isEmpty()) edits.add(new QueryScopeEdits.Edit(QueryScopeEdits.select(s,s.getWhere(),s.getHaving(),SqlNodeList.EMPTY,s.getOffset(),s.getFetch()),
                    "Remove ORDER BY", new Change("REMOVE","ORDER_BY",path,NodeToSql.toSql(s.getOrderList(),p),null)));
                if (s.getOffset() != null || s.getFetch() != null) edits.add(new QueryScopeEdits.Edit(QueryScopeEdits.select(s,s.getWhere(),s.getHaving(),s.getOrderList(),null,null),
                    "Remove LIMIT/OFFSET", new Change("REMOVE","PAGINATION",path,NodeToSql.toSql(s,p),null)));
            }
            return edits;
        });
        for (var candidate : candidates) {
            String sql = NodeToSql.toSql(candidate.node(), p);
            String reason = "sqlserver".equalsIgnoreCase(dialect)
                ? com.bitdata.sqlworkbench.sql.tsql.TsqlQueryLegality.rejectionReason(candidate.node()) : null;
            if (reason != null) {
                issues.add(new Issue(reason, "Candidate ordering/pagination is not independently executable",
                    name(), candidate.change().scopePath(), null, sql));
                continue;
            }
            fragments.add(new DecomposedFragment(FragmentType.STRIPPED,candidate.label(),sql,name(),true,
                "Other clauses retained; results belong to this experiment",List.of(),
                new Effect(null,null,"PRESERVED",List.of(candidate.change()),List.of(),List.of())));
        }
        return new RuleEvaluation(fragments, issues);
    }
}
