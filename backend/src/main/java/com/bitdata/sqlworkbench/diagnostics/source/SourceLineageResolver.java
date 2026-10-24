package com.bitdata.sqlworkbench.diagnostics.source;

import com.bitdata.sqlworkbench.sql.SqlDialectResolver;
import org.apache.calcite.avatica.util.Casing;
import org.apache.calcite.sql.*;
import org.apache.calcite.sql.parser.*;
import java.sql.SQLException;
import java.util.*;

/** Structural source evidence. It does not infer that a source row satisfies the original query. */
final class SourceLineageResolver {
    interface Catalog {
        Relation read(List<String> name, String defaultSchema) throws SQLException;
    }
    record Relation(String schema, String name, List<String> columns, String viewDefinition, Map<String,String> columnTypes) {
        Relation(String schema,String name,List<String> columns,String viewDefinition) {this(schema,name,columns,viewDefinition,Map.of());}
        Relation { columns=List.copyOf(columns);columnTypes=Map.copyOf(columnTypes); }
    }
    record Leaf(String occurrence, Relation relation, List<String> path) {
        Leaf { path=List.copyOf(path); }
    }
    record Binding(Leaf leaf, SqlNode expression) {}
    record Column(String name, List<Binding> bindings, String errorReason, String certifiedType) {
        Column(String name,List<Binding> bindings,String errorReason) {this(name,bindings,errorReason,null);}
        Column { bindings=List.copyOf(bindings); }
    }
    record Result(List<Column> columns, List<Leaf> leaves) {
        Result { columns=List.copyOf(columns); leaves=List.copyOf(leaves); }
    }
    private record Cte(SqlWithItem item, Map<String,Cte> scope) {}
    private record Input(String qualifier, List<Column> columns) {}
    private static final Set<String> SCALAR=Set.of("CONCAT","COALESCE","NULLIF","LOWER","UPPER","TRIM",
        "SUBSTRING","CHAR_LENGTH","CHARACTER_LENGTH","ABS","CEIL","CEILING","FLOOR","ROUND");
    private static final Set<String> AGGREGATES=Set.of("SUM","COUNT","AVG","MIN","MAX","ARRAY_AGG",
        "STRING_AGG","GROUP_CONCAT","JSON_AGG","JSON_ARRAYAGG","BOOL_AND","BOOL_OR","STDDEV","VARIANCE");
    private static final Set<SqlKind> OPERATORS=EnumSet.of(SqlKind.PLUS,SqlKind.MINUS,SqlKind.TIMES,
        SqlKind.DIVIDE,SqlKind.MOD,SqlKind.MINUS_PREFIX,SqlKind.PLUS_PREFIX,SqlKind.CAST,SqlKind.CASE,
        SqlKind.EQUALS,SqlKind.NOT_EQUALS,SqlKind.LESS_THAN,SqlKind.LESS_THAN_OR_EQUAL,
        SqlKind.GREATER_THAN,SqlKind.GREATER_THAN_OR_EQUAL,SqlKind.AND,SqlKind.OR,SqlKind.NOT,
        SqlKind.IS_NULL,SqlKind.IS_NOT_NULL);
    private final String dialect;
    private final SqlDialectResolver resolver;
    private final Catalog catalog;
    private final List<Leaf> leaves=new ArrayList<>();
    private final Set<String> activeViews=new HashSet<>();
    private int visits;
    SourceLineageResolver(String dialect, SqlDialectResolver resolver, Catalog catalog) {
        this.dialect=dialect;this.resolver=resolver;this.catalog=catalog;
    }
    Result resolve(String sql) throws SQLException,SqlParseException {
        // A resolver instance is used once; otherwise occurrence identities could leak across requests.
        if(visits!=0)throw new IllegalStateException("Lineage resolver already used");
        var columns=query(parse(sql),Map.of(),null,List.of("query"),0);
        return new Result(columns,leaves);
    }
    private SqlNode parse(String sql) throws SqlParseException {
        return SqlParser.create(sql,resolver.parserConfig(dialect)
            .withUnquotedCasing(dialect.equals("postgresql")?Casing.TO_LOWER:Casing.UNCHANGED)).parseQuery();
    }
    private void limit(int depth) {
        if(depth>24 || ++visits>256 || leaves.size()>64)throw fail("SOURCE_LINEAGE_LIMIT");
    }
    private List<Column> query(SqlNode node,Map<String,Cte> ctes,String schema,List<String> path,int depth)
            throws SQLException,SqlParseException {
        limit(depth);
        if(node instanceof SqlOrderBy order)return query(order.query,ctes,schema,path,depth+1);
        if(node instanceof SqlWith with) {
            Map<String,Cte> scope=new LinkedHashMap<>(ctes);
            for(SqlNode n:with.withList) {
                var item=(SqlWithItem)n;
                if(item.recursive!=null&&item.recursive.booleanValue())throw fail("SOURCE_RECURSIVE_UNSUPPORTED");
                String name=canonical(item.name.getSimple());
                scope.put(name,new Cte(item,Map.copyOf(scope)));
            }
            return query(with.body,scope,schema,path,depth+1);
        }
        if(node instanceof SqlCall call && Set.of(SqlKind.UNION,SqlKind.INTERSECT,SqlKind.EXCEPT).contains(call.getKind())) {
            var left=query(call.operand(0),ctes,schema,append(path,call.getKind()+":left"),depth+1);
            var right=query(call.operand(1),ctes,schema,append(path,call.getKind()+":right"),depth+1);
            if(left.size()!=right.size())throw fail("SOURCE_SET_ARITY_MISMATCH");
            List<Column> result=new ArrayList<>();
            for(int i=0;i<left.size();i++) {
                Column a=left.get(i),b=right.get(i);List<Binding> bindings=new ArrayList<>(a.bindings());bindings.addAll(b.bindings());
                // Set operations coerce each branch BEFORE an outer expression is evaluated.
                // Substitution is safe only when the complete declared types (including collation)
                // match; unsupported/computed branch types must remain observable unknowns.
                String error=a.errorReason()!=null?a.errorReason():b.errorReason();
                boolean identical=stableSetType(a.certifiedType())&&a.certifiedType().equals(b.certifiedType());
                if(error==null&&!identical)error="SOURCE_SET_COERCION_UNVERIFIED";
                result.add(new Column(a.name(),error==null?bindings:List.of(),error,identical?a.certifiedType():null));
            }
            return result;
        }
        if(!(node instanceof SqlSelect select))throw fail("SOURCE_STRUCTURE_UNSUPPORTED");
        var inputs=from(select.getFrom(),ctes,schema,path,depth+1);
        List<Column> result=new ArrayList<>();
        for(SqlNode item:select.getSelectList()) {
            SqlNode expression=item;String label=null;
            if(item instanceof SqlCall call&&call.getKind()==SqlKind.AS) {
                expression=call.operand(0);label=((SqlIdentifier)call.operand(1)).getSimple();
            }
            if(expression instanceof SqlIdentifier id&&id.isStar()) {
                List<Input> selected=id.names.size()==1?inputs:inputs.stream()
                    .filter(i->same(i.qualifier(),id.names.get(id.names.size()-2))).toList();
                if(selected.isEmpty())throw fail("SOURCE_QUALIFIER_UNAVAILABLE");
                for(var input:selected)result.addAll(input.columns());
            } else {
                if(label==null)label=expression instanceof SqlIdentifier id?id.names.get(id.names.size()-1):"";
                try {
                    var bindings=expression(expression,inputs);
                    result.add(new Column(label,bindings,bindings.isEmpty()?"SOURCE_CONSTANT_KEY":null,projectedType(expression,inputs)));
                } catch(PhysicalSourcePlanService.Unverified e) {result.add(new Column(label,List.of(),e.code));}
            }
        }
        return result;
    }
    private List<Input> from(SqlNode node,Map<String,Cte> ctes,String schema,List<String> path,int depth)
            throws SQLException,SqlParseException {
        limit(depth);
        if(node==null)return List.of();
        if(node instanceof SqlJoin join) {
            var result=new ArrayList<>(from(join.getLeft(),ctes,schema,append(path,"join:left"),depth+1));
            result.addAll(from(join.getRight(),ctes,schema,append(path,"join:right"),depth+1));return result;
        }
        if(node instanceof SqlCall call&&call.getKind()==SqlKind.AS) {
            if(call.operandCount()!=2)throw fail("SOURCE_RELATION_COLUMN_LIST_UNSUPPORTED");
            String alias=((SqlIdentifier)call.operand(1)).getSimple();
            var inputs=from(call.operand(0),ctes,schema,append(path,"alias:"+alias),depth+1);
            if(inputs.size()!=1)throw fail("SOURCE_JOIN_ALIAS_UNSUPPORTED");
            return List.of(new Input(alias,inputs.get(0).columns()));
        }
        if(node instanceof SqlIdentifier id) {
            if(id.names.size()>2)throw fail("SOURCE_RELATION_NAME_UNSUPPORTED");
            String name=id.names.get(id.names.size()-1);
            Cte cte=id.names.size()==1?ctes.get(canonical(name)):null;
            if(cte!=null) {
                var columns=query(cte.item().query,cte.scope(),schema,append(path,"cte:"+name),depth+1);
                return List.of(new Input(name,rename(columns,cte.item().columnList)));
            }
            Relation relation=catalog.read(id.names,schema);
            if(relation==null)throw fail("SOURCE_RELATION_UNAVAILABLE");
            if(relation.viewDefinition()!=null) {
                String identity=relation.schema()+"."+relation.name();
                if(!activeViews.add(identity))throw fail("SOURCE_VIEW_CYCLE");
                try {
                    // Views resolve their own unqualified relations, not the outer query's CTE namespace.
                    String definition=relation.viewDefinition().stripTrailing();
                    // pg_get_viewdef appends one terminator; parseQuery deliberately rejects it.
                    if(definition.endsWith(";"))definition=definition.substring(0,definition.length()-1);
                    // pg_get_viewdef deparses names relative to THIS connection's search_path.
                    // A view in another schema may refer to an unqualified public table.
                    var columns=query(parse(definition),Map.of(),dialect.equals("postgresql")?null:relation.schema(),append(path,"view:"+identity),depth+1);
                    if(columns.size()!=relation.columns().size())throw fail("SOURCE_VIEW_ARITY_MISMATCH");
                    List<Column> renamed=new ArrayList<>();
                    for(int i=0;i<columns.size();i++)renamed.add(new Column(relation.columns().get(i),columns.get(i).bindings(),columns.get(i).errorReason(),columns.get(i).certifiedType()));
                    return List.of(new Input(name,renamed));
                } finally {activeViews.remove(identity);}
            }
            if(leaves.size()>=64)throw fail("SOURCE_LINEAGE_LIMIT");
            var leaf=new Leaf("source-"+(leaves.size()+1),relation,append(path,"table:"+relation.schema()+"."+relation.name()));leaves.add(leaf);
            var columns=relation.columns().stream().map(column->new Column(column,List.of(new Binding(leaf,
                new SqlIdentifier(List.of("src",column),SqlParserPos.ZERO))),null,relation.columnTypes().get(column))).toList();
            return List.of(new Input(name,columns));
        }
        return List.of(new Input("",query(node,ctes,schema,append(path,"derived"),depth+1)));
    }
    private List<Column> rename(List<Column> columns,SqlNodeList names) {
        if(names==null)return columns;
        if(columns.size()!=names.size())throw fail("SOURCE_CTE_ARITY_MISMATCH");
        List<Column> result=new ArrayList<>();
        for(int i=0;i<columns.size();i++)result.add(new Column(((SqlIdentifier)names.get(i)).getSimple(),columns.get(i).bindings(),columns.get(i).errorReason(),columns.get(i).certifiedType()));
        return result;
    }
    private boolean stableSetType(String type) {
        if(type==null)return false;
        // MySQL ENUM/SET lose their ordinal-bearing type at a set boundary even if
        // both declarations match. Certify only scalar families whose representation
        // is preserved; unknown vendor types cannot silently inherit source semantics.
        return !dialect.equals("mysql")||Set.of("tinyint","smallint","mediumint","int","integer","bigint","decimal","numeric","float","double","real","varchar","tinytext","text","mediumtext","longtext","varbinary","tinyblob","blob","mediumblob","longblob").contains(type.toLowerCase(Locale.ROOT).split("[(: ]",2)[0]);
    }
    private String projectedType(SqlNode node,List<Input> inputs) {
        if(!(node instanceof SqlIdentifier id))return null;
        List<Column> matches=new ArrayList<>();
        for(Input input:inputs) {
            if(id.names.size()>1&&!same(input.qualifier(),id.names.get(id.names.size()-2)))continue;
            for(Column column:input.columns())if(same(column.name(),id.names.get(id.names.size()-1)))matches.add(column);
        }
        return matches.size()==1?matches.get(0).certifiedType():null;
    }
    private List<Binding> expression(SqlNode node,List<Input> inputs) {
        limit(0);
        if(node instanceof SqlIdentifier id) {
            String name=id.names.get(id.names.size()-1);List<Column> matches=new ArrayList<>();
            for(Input input:inputs) {
                if(id.names.size()>1&&!same(input.qualifier(),id.names.get(id.names.size()-2)))continue;
                for(Column column:input.columns())if(same(column.name(),name))matches.add(column);
            }
            if(matches.size()!=1)throw fail(matches.isEmpty()?"SOURCE_COLUMN_UNAVAILABLE":"SOURCE_COLUMN_AMBIGUOUS");
            Column match=matches.get(0);if(match.errorReason()!=null)throw fail(match.errorReason());return match.bindings();
        }
        if(node==null||node instanceof SqlLiteral||node instanceof SqlDataTypeSpec)return List.of();
        if(node instanceof SqlNodeList list) {
            List<Binding> refs=new ArrayList<>();for(SqlNode n:list)refs.addAll(expression(n,inputs));return refs;
        }
        if(!(node instanceof SqlCall call))throw fail("SOURCE_EXPRESSION_UNSUPPORTED");
        if(aggregate(call))throw fail("SOURCE_AGGREGATE_KEY");
        if(!OPERATORS.contains(call.getKind())&&!SCALAR.contains(call.getOperator().getName().toUpperCase(Locale.ROOT)))throw fail("SOURCE_EXPRESSION_UNSUPPORTED");
        // Rebuild separately for each leaf/branch, substituting its already-resolved projection.
        Set<Leaf> targets=new LinkedHashSet<>();
        collectTargets(node,inputs,targets);
        if(targets.isEmpty())return List.of();
        List<Binding> result=new ArrayList<>();
        for(Leaf leaf:targets)result.add(new Binding(leaf,rewrite(node,inputs,leaf)));
        return result;
    }
    private void collectTargets(SqlNode node,List<Input> inputs,Set<Leaf> targets) {
        limit(0);
        if(node instanceof SqlIdentifier) {for(Binding b:expression(node,inputs))targets.add(b.leaf());}
        else if(node instanceof SqlNodeList list) {for(SqlNode n:list)collectTargets(n,inputs,targets);}
        else if(node instanceof SqlCall call) {
            if(aggregate(call))throw fail("SOURCE_AGGREGATE_KEY");
            if(!OPERATORS.contains(call.getKind())&&!SCALAR.contains(call.getOperator().getName().toUpperCase(Locale.ROOT)))throw fail("SOURCE_EXPRESSION_UNSUPPORTED");
            for(SqlNode operand:call.getOperandList())collectTargets(operand,inputs,targets);
        }
    }
    private SqlNode rewrite(SqlNode node,List<Input> inputs,Leaf leaf) {
        limit(0);
        if(node instanceof SqlIdentifier) {
            var matches=expression(node,inputs).stream().filter(b->b.leaf().equals(leaf)).toList();
            if(matches.size()!=1)throw fail("SOURCE_EXPRESSION_MULTIPLE_RELATIONS");return matches.get(0).expression();
        }
        if(node instanceof SqlNodeList list) {
            return new SqlNodeList(list.getList().stream().map(n->rewrite(n,inputs,leaf)).toList(),list.getParserPosition());
        }
        if(node instanceof SqlCall call)return call.getOperator().createCall(call.getFunctionQuantifier(),call.getParserPosition(),
            call.getOperandList().stream().map(n->rewrite(n,inputs,leaf)).toArray(SqlNode[]::new));
        return node;
    }
    private String canonical(String name) {return dialect.equals("mysql")?name.toLowerCase(Locale.ROOT):name;}
    private static boolean aggregate(SqlCall call) {return call.getOperator().isAggregator()||call.getKind()==SqlKind.OVER||AGGREGATES.contains(call.getOperator().getName().toUpperCase(Locale.ROOT));}
    private boolean same(String a,String b) {return PhysicalSourcePlanService.same(a,b,dialect);}
    private static List<String> append(List<String> path,String item) {var result=new ArrayList<>(path);result.add(item);return result;}
    private static PhysicalSourcePlanService.Unverified fail(String code) {return new PhysicalSourcePlanService.Unverified(code);}
}
