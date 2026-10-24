package com.bitdata.sqlworkbench.sql;

import com.bitdata.sqlworkbench.connection.ConnectionService;
import com.bitdata.sqlworkbench.sql.model.DecompositionResult.Issue;
import org.apache.calcite.sql.*;
import org.apache.calcite.sql.util.SqlShuttle;
import org.springframework.stereotype.Service;

import java.sql.*;
import java.util.*;

/** Request-local ownership evidence. Never guesses across unknown relations or scopes. */
@Service
public class ConnectedColumnResolver {
    public record Resolution(SqlNode node, List<Issue> issues) {}
    private final ConnectionService connections;
    private final SqlDialectResolver dialects;

    public ConnectedColumnResolver(ConnectionService connections, SqlDialectResolver dialects) {
        this.connections = connections;
        this.dialects = dialects;
    }

    public Resolution resolve(SqlNode original, String dialect, String connectionId) {
        if (connectionId == null || connectionId.isBlank()) return new Resolution(original, List.of());
        try (Connection connection = connections.openJdbcConnection(connectionId)) {
            Worker worker = new Worker(connection, dialects.getProfile(dialect), dialect);
            // SqlShuttle copies calls/lists before scope-specific mutation; the source AST is evidence.
            SqlNode copy = original.accept(new SqlShuttle() {
                @Override public SqlNode visit(SqlNodeList list) {
                    List<SqlNode> items = new ArrayList<>();
                    for (SqlNode item : list) items.add(item == null ? null : item.accept(this));
                    return new SqlNodeList(items, list.getParserPosition());
                }
                @Override public SqlNode visit(SqlCall call) {
                    SqlNode[] operands = new SqlNode[call.operandCount()];
                    for (int i = 0; i < operands.length; i++) {
                        SqlNode operand = call.operand(i);
                        operands[i] = operand == null ? null : operand.accept(this);
                    }
                    return call.getOperator().createCall(call.getFunctionQuantifier(), call.getParserPosition(), operands);
                }
            });
            worker.query(copy, new HashMap<>());
            return new Resolution(copy, List.copyOf(worker.issues));
        } catch (Exception e) {
            // Preserve usable schema-free rules, but never present metadata failure as success.
            return new Resolution(original, List.of(new Issue("SCHEMA_METADATA_UNAVAILABLE",
                "Could not read column metadata from the selected connection (" + e.getClass().getSimpleName() + ")",
                null, "query", null, null)));
        }
    }

    private static final class Worker {
        record Input(SqlIdentifier alias, List<String> columns) {}
        final Connection connection;
        final DialectProfile profile;
        final boolean mysql;
        final Map<String, List<String>> tables = new HashMap<>();
        final List<Issue> issues = new ArrayList<>();
        final long deadline = System.nanoTime() + 5_000_000_000L;
        Worker(Connection connection, DialectProfile profile, String dialect) {
            this.connection = connection; this.profile = profile; this.mysql = "mysql".equalsIgnoreCase(dialect);
        }
        String key(SqlIdentifier id) {
            int i = id.names.size() - 1;
            String value = id.names.get(i);
            return mysql || !id.isComponentQuoted(i) ? value.toLowerCase(Locale.ROOT) : value;
        }
        String columnKey(String name) { return mysql ? name.toLowerCase(Locale.ROOT) : name; }
        List<String> query(SqlNode n, Map<String, List<String>> ctes) throws SQLException {
            if (n instanceof SqlWith with) {
                Map<String, List<String>> local = new HashMap<>(ctes);
                // Register all names as unknown first: recursive/forward references never hit a physical table.
                for (SqlNode item : with.withList) local.put(key(((SqlWithItem) item).name), null);
                for (SqlNode item : with.withList) {
                    SqlWithItem cte = (SqlWithItem) item;
                    List<String> columns = query(cte.query, local);
                    if (cte.columnList != null && columns != null && cte.columnList.size() == columns.size())
                        columns = cte.columnList.getList().stream().map(x -> key((SqlIdentifier)x)).toList();
                    local.put(key(cte.name), columns);
                }
                return query(with.body, local);
            }
            if (n instanceof SqlOrderBy order) {
                List<String> columns = query(order.query, ctes);
                if (order.query instanceof SqlSelect select && !unsafeJoin(select.getFrom())) {
                    List<Input> inputs = inputs(select.getFrom(), ctes);
                    SqlNodeList qualified = (SqlNodeList) expression(order.orderList, inputs, outputAliases(select));
                    for (int i=0; i<qualified.size(); i++) order.orderList.set(i, qualified.get(i));
                }
                return columns;
            }
            if (!(n instanceof SqlSelect select)) return null;
            List<Input> inputs = inputs(select.getFrom(), ctes);
            if (!unsafeJoin(select.getFrom())) {
                Set<String> aliases = outputAliases(select);
                select.setSelectList((SqlNodeList) expression(select.getSelectList(), inputs, Set.of()));
                select.setWhere(expression(select.getWhere(), inputs, Set.of()));
                select.setGroupBy((SqlNodeList) expression(select.getGroup(), inputs, aliases));
                select.setHaving(expression(select.getHaving(), inputs, mysql ? aliases : Set.of()));
                select.setOrderBy((SqlNodeList) expression(select.getOrderList(), inputs, aliases));
                qualifyOn(select.getFrom(), inputs);
            }
            List<String> columns = new ArrayList<>();
            for (SqlNode item : select.getSelectList()) {
                if (item instanceof SqlCall call && call.getKind() == SqlKind.AS)
                    columns.add(key(call.operand(1)));
                else if (item instanceof SqlIdentifier id && !id.isStar())
                    columns.add(key(id));
                else if (item instanceof SqlIdentifier id && id.isStar()) {
                    List<Input> selected = inputs.stream().filter(in -> id.names.size() == 1 || key(in.alias).equals(
                        mysql ? id.names.get(0).toLowerCase(Locale.ROOT) : id.names.get(0))).toList();
                    if (selected.isEmpty() || selected.stream().anyMatch(in -> in.columns == null)) return null;
                    for (Input in : selected) columns.addAll(in.columns);
                } else return null; // Unnamed expressions have dialect-specific output names.
            }
            return columns;
        }
        List<Input> inputs(SqlNode from, Map<String, List<String>> ctes) throws SQLException {
            if (from == null) return List.of();
            if (from instanceof SqlJoin join) {
                List<Input> all = new ArrayList<>(inputs(join.getLeft(), ctes));
                all.addAll(inputs(join.getRight(), ctes)); return all;
            }
            SqlIdentifier alias = null;
            if (from instanceof SqlCall call && call.getKind() == SqlKind.AS) {
                if (call.operandCount() != 2) return List.of(new Input((SqlIdentifier)call.operand(1), null));
                alias = call.operand(1); from = call.operand(0);
            }
            if (from instanceof SqlIdentifier table) {
                if (alias == null) alias = table.getComponent(table.names.size()-1);
                List<String> columns;
                if (table.names.size() == 1 && ctes.containsKey(key(table))) columns = ctes.get(key(table));
                else {
                    String sql = NodeToSql.toSql(table, profile);
                    columns = tables.get(sql);
                    if (columns == null) {
                        if (tables.size() >= 32 || System.nanoTime() >= deadline) throw new SQLTimeoutException();
                        // A generated table-only zero-row query respects catalog/search_path and avoids JDBC pattern matching.
                        try (Statement statement = connection.createStatement()) {
                            statement.setQueryTimeout(Math.max(1, (int)Math.ceil((deadline-System.nanoTime())/1e9)));
                            try (ResultSet rs = statement.executeQuery("SELECT * FROM " + sql + " WHERE 1=0")) {
                                ResultSetMetaData metadata = rs.getMetaData();
                                columns = new ArrayList<>();
                                for (int i=1; i<=metadata.getColumnCount(); i++) columns.add(metadata.getColumnLabel(i));
                            }
                        }
                        tables.put(sql, columns);
                    }
                }
                return List.of(new Input(alias, columns));
            }
            List<String> columns = query(from, ctes);
            return List.of(new Input(alias, columns));
        }
        boolean unsafeJoin(SqlNode from) {
            if (!(from instanceof SqlJoin join)) return false;
            return join.isNatural() || join.getConditionType() == JoinConditionType.USING
                || unsafeJoin(join.getLeft()) || unsafeJoin(join.getRight());
        }
        void qualifyOn(SqlNode from, List<Input> inputs) {
            if (from instanceof SqlJoin join) {
                // ON sees only this join's subtree, not later comma/JOIN operands.
                if (join.getCondition() != null) join.setOperand(5, expression(join.getCondition(), inputs, Set.of()));
                // Restrict nested ON using relation aliases present in that subtree.
                qualifyOn(join.getLeft(), subset(join.getLeft(), inputs));
                qualifyOn(join.getRight(), subset(join.getRight(), inputs));
            }
        }
        List<Input> subset(SqlNode from, List<Input> inputs) {
            Set<String> names = new HashSet<>(); collectAliases(from, names);
            return inputs.stream().filter(in -> in.alias != null && names.contains(key(in.alias))).toList();
        }
        void collectAliases(SqlNode from, Set<String> names) {
            if (from instanceof SqlJoin join) { collectAliases(join.getLeft(), names); collectAliases(join.getRight(), names); }
            else if (from instanceof SqlIdentifier id) names.add(key(id));
            else if (from instanceof SqlCall call && call.getKind()==SqlKind.AS) names.add(key(call.operand(1)));
        }
        Set<String> outputAliases(SqlSelect select) {
            Set<String> result = new HashSet<>();
            for (SqlNode item : select.getSelectList())
                if (item instanceof SqlCall call && call.getKind()==SqlKind.AS) result.add(key(call.operand(1)));
            return result;
        }
        SqlNode expression(SqlNode node, List<Input> inputs, Set<String> aliases) {
            if (node == null) return null;
            return node.accept(new SqlShuttle() {
                @Override public SqlNode visit(SqlIdentifier id) {
                    if (id.names.size()!=1 || id.isStar() || aliases.contains(key(id))) return id;
                    if (inputs.stream().anyMatch(in -> in.columns==null || in.alias==null)) return id;
                    List<Input> owners = new ArrayList<>();
                    for (Input in : inputs) for (String column : in.columns)
                        if (columnKey(column).equals(key(id))) owners.add(in);
                    if (owners.size()!=1) {
                        issues.add(new Issue(owners.isEmpty()?"COLUMN_NOT_FOUND":"AMBIGUOUS_COLUMN",
                            "Column " + id.getSimple() + " has " + owners.size() + " matching columns in this scope",
                            "join-split", "query", null, null));
                        return id;
                    }
                    SqlIdentifier alias = owners.get(0).alias;
                    return new SqlIdentifier(List.of(alias.getSimple(), id.getSimple()), null, id.getParserPosition(),
                        List.of(alias.getComponentParserPosition(0), id.getParserPosition()));
                }
                @Override public SqlNode visit(SqlCall call) {
                    // Nested/correlated scopes and window names require separate ownership evidence.
                    if (call instanceof SqlSelect || call instanceof SqlWith || call instanceof SqlOrderBy || call instanceof SqlWindow)
                        return call;
                    if (call.getKind()==SqlKind.AS || call.getKind()==SqlKind.CAST || call.getKind()==SqlKind.OVER) {
                        SqlCall copy = (SqlCall)call.clone(call.getParserPosition());
                        copy.setOperand(0, call.operand(0).accept(this)); return copy;
                    }
                    return super.visit(call);
                }
            });
        }
    }
}
