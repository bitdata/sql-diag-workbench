package com.bitdata.sqlworkbench.sql.decompose;

import org.apache.calcite.sql.*;
import java.util.*;
import java.util.function.Function;
import com.bitdata.sqlworkbench.sql.model.DecompositionResult.Change;

/** Rebuild only the selected query scope; siblings and every enclosing suffix survive. */
final class QueryScopeEdits {
    record Edit(SqlNode node, String label, Change change) {}
    static List<Edit> map(SqlNode node, String path, Function<Scope, List<Edit>> edit) {
        List<Edit> result = new ArrayList<>();
        for (Edit e : edit.apply(new Scope(node, path))) result.add(e);
        if (node instanceof SqlOrderBy ob) {
            for (Edit e : map(ob.query, path + "/query", edit))
                result.add(new Edit(new SqlOrderBy(ob.getParserPosition(), e.node(), ob.orderList, ob.offset, ob.fetch), e.label(), e.change()));
        } else if (node instanceof SqlWith with) {
            for (int i = 0; i < with.withList.size(); i++) {
                SqlWithItem item = (SqlWithItem) with.withList.get(i);
                for (Edit e : map(item.query, path + "/cte[" + i + "]", edit)) {
                    SqlNodeList items = new SqlNodeList(with.withList.getParserPosition());
                    for (int j = 0; j < with.withList.size(); j++) {
                        if (j != i) items.add(with.withList.get(j));
                        else { SqlWithItem copy = (SqlWithItem) item.clone(item.getParserPosition()); copy.setOperand(2, e.node()); items.add(copy); }
                    }
                    result.add(new Edit(new SqlWith(with.getParserPosition(), items, with.body), e.label(), e.change()));
                }
            }
            for (Edit e : map(with.body, path + "/body", edit))
                result.add(new Edit(new SqlWith(with.getParserPosition(), with.withList, e.node()), e.label(), e.change()));
        }
        return result;
    }
    record Scope(SqlNode node, String path) {}
    static SqlSelect select(SqlSelect s, SqlNode where, SqlNode having, SqlNodeList order, SqlNode offset, SqlNode fetch) {
        return new SqlSelect(s.getParserPosition(), (SqlNodeList)s.getOperandList().get(0),
            s.getSelectList(), s.getFrom(), where, s.getGroup(), having, s.getWindowList(),
            order, offset, fetch, s.getHints());
    }
}
