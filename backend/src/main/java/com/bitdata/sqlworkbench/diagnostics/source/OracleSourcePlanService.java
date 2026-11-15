package com.bitdata.sqlworkbench.diagnostics.source;

import com.bitdata.sqlworkbench.diagnostics.trace.*;
import com.bitdata.sqlworkbench.sql.*;
import org.apache.calcite.sql.*;
import org.apache.calcite.sql.parser.SqlParser;
import org.springframework.stereotype.Service;

import java.sql.*;
import java.time.Instant;
import java.util.*;

/** Current-owner TABLE / direct NUMBER / complete validated primary-key certification. */
@Service
public final class OracleSourcePlanService {
    private final SqlDialectResolver resolver;
    private final SelectOnlyGuard guard;

    public OracleSourcePlanService(SqlDialectResolver resolver, SelectOnlyGuard guard) {
        this.resolver=resolver; this.guard=guard;
    }

    private record NumericType(String precision, String scale) {
        String signature() { return "NUMBER("+precision+","+scale+")"; }
    }
    private record Table(Map<String,NumericType> numbers, List<String> primaryKey) {}
    private record Output(String label, int type, int precision, int scale) {}
    private record Accepted(int keyIndex, String output, String column, SourceLineageResolver.Binding binding) {}

    private static PhysicalSourcePlanService.Unverified fail(String code) {
        return new PhysicalSourcePlanService.Unverified(code);
    }
    private static String q(String name) { return "\""+name.replace("\"","\"\"")+"\""; }

    private static List<List<String>> query(Connection connection, PreparationJdbc task, String sql, String... parameters) throws SQLException {
        task.checkpoint();
        try (PreparedStatement statement=connection.prepareStatement(sql)) {
            task.configure(statement);
            statement.setMaxRows(257);
            for(int i=0;i<parameters.length;i++)statement.setString(i+1,parameters[i]);
            try(ResultSet rows=statement.executeQuery()) {
                List<List<String>> result=new ArrayList<>();
                int count=rows.getMetaData().getColumnCount();
                while(rows.next()) {
                    if(result.size()==256)throw fail("SOURCE_CATALOG_LIMIT");
                    List<String> row=new ArrayList<>();
                    for(int i=1;i<=count;i++)row.add(rows.getString(i));
                    result.add(row);
                    task.checkpoint();
                }
                return result;
            }
        } finally { task.clearStatement(); }
    }

    private static List<Output> metadata(Connection connection, PreparationJdbc task, String sql) throws Exception {
        task.checkpoint();
        try(PreparedStatement statement=connection.prepareStatement(OracleObservationSql.metadata(sql).sql())) {
            task.configure(statement);
            try(ResultSet rows=statement.executeQuery()) {
                var meta=rows.getMetaData(); List<Output> output=new ArrayList<>(); Set<String> labels=new HashSet<>();
                for(int i=1;i<=meta.getColumnCount();i++) {
                    String label=meta.getColumnLabel(i);
                    if(label==null||label.isBlank()||!labels.add(label))throw fail("OUTPUT_LABEL_AMBIGUOUS");
                    output.add(new Output(label,meta.getColumnType(i),meta.getPrecision(i),meta.getScale(i)));
                }
                task.checkpoint();
                return List.copyOf(output);
            }
        } finally { task.clearStatement(); }
    }

    public ComplexSourcePlanService.Plan inspect(PhysicalSourcePlanService.Request request, boolean direct,
                                                Connection connection, PreparationJdbc task) throws Exception {
        if(request==null||request.keys()==null||request.keys().isEmpty()||request.keys().size()>16||request.keys().stream().anyMatch(Objects::isNull))throw fail("SOURCE_INPUT_INVALID");
        if(request.keys().stream().anyMatch(k->k.type()==null))throw fail("TYPED_KEY_REQUIRED");
        guard.requireSingleSelect(request.sql(),"oracle");
        if(direct) {
            SqlNode root=SqlParser.create(request.sql(),resolver.parserConfig("oracle")).parseQuery();
            if(root instanceof SqlOrderBy order)root=order.query;
            if(!(root instanceof SqlSelect select))throw fail("DIRECT_PHYSICAL_SOURCE_REQUIRED");
            SqlNode from=select.getFrom();
            if(from instanceof SqlCall alias&&alias.getKind()==SqlKind.AS)from=alias.operand(0);
            if(!(from instanceof SqlIdentifier))throw fail("DIRECT_PHYSICAL_SOURCE_REQUIRED");
        }
        String metadataSql=OracleObservationSql.metadata(request.sql()).sql();
        var identity=query(connection,task,"SELECT SYS_CONTEXT('USERENV','SESSION_USER'), SYS_CONTEXT('USERENV','CURRENT_SCHEMA') FROM dual");
        if(identity.size()!=1||identity.get(0).get(0)==null||!identity.get(0).get(0).equals(identity.get(0).get(1)))throw fail("ORACLE_SOURCE_OWNER_UNVERIFIED");
        String owner=identity.get(0).get(0);
        Map<String,Table> tables=new HashMap<>();
        var lineage=new SourceLineageResolver("oracle",resolver,(names,defaultSchema)->{
            if(names.isEmpty()||names.size()>2||names.stream().anyMatch(n->n.contains("@")))throw fail("ORACLE_SOURCE_NAME_UNSUPPORTED");
            String schema=names.size()==2?names.get(0):owner, name=names.get(names.size()-1);
            if(!owner.equals(schema))return new SourceLineageResolver.Relation(schema,name,List.of(),null,Map.of(),"ORACLE_SOURCE_OWNER_UNVERIFIED");
            var objects=query(connection,task,"SELECT object_type,status FROM all_objects WHERE owner=? AND object_name=? AND subobject_name IS NULL AND object_type IN ('TABLE','VIEW','SYNONYM','MATERIALIZED VIEW')",schema,name);
            if(objects.isEmpty())return new SourceLineageResolver.Relation(schema,name,List.of(),null,Map.of(),"SOURCE_RELATION_UNAVAILABLE");
            if(objects.size()!=1)throw fail("ORACLE_SOURCE_OBJECT_AMBIGUOUS");
            var columns=query(connection,task,"SELECT column_name,data_type,data_precision,data_scale FROM all_tab_columns WHERE owner=? AND table_name=? ORDER BY column_id",schema,name);
            var namesFound=columns.stream().map(row->row.get(0)).toList();
            if(!"TABLE".equals(objects.get(0).get(0)))return new SourceLineageResolver.Relation(schema,name,namesFound,null,Map.of(),"ORACLE_SOURCE_NON_TABLE_UNVERIFIED");
            if(!"VALID".equals(objects.get(0).get(1)))return new SourceLineageResolver.Relation(schema,name,namesFound,null,Map.of(),"ORACLE_SOURCE_OBJECT_INVALID");
            Map<String,NumericType> numbers=new LinkedHashMap<>(); Map<String,String> types=new LinkedHashMap<>();
            for(var row:columns)if("NUMBER".equals(row.get(1))) {
                var type=new NumericType(row.get(2),row.get(3));numbers.put(row.get(0),type);types.put(row.get(0),type.signature());
            }
            var pk=query(connection,task,"SELECT cc.column_name FROM all_constraints co JOIN all_cons_columns cc ON co.owner=cc.owner AND co.constraint_name=cc.constraint_name AND co.table_name=cc.table_name WHERE co.owner=? AND co.table_name=? AND co.constraint_type='P' AND co.status='ENABLED' AND co.validated='VALIDATED' ORDER BY cc.position",schema,name);
            tables.put(schema+"."+name,new Table(Map.copyOf(numbers),pk.stream().map(row->row.get(0)).toList()));
            return new SourceLineageResolver.Relation(schema,name,namesFound,null,types);
        }).resolve(request.sql());
        var output=metadata(connection,task,request.sql());
        if(output.size()!=lineage.columns().size())throw fail("SOURCE_OUTPUT_ARITY_MISMATCH");
        for(int i=0;i<output.size();i++)if(!output.get(i).label().equals(lineage.columns().get(i).name()))throw fail("SOURCE_OUTPUT_LABEL_MISMATCH");
        List<ComplexSourcePlanService.Issue> issues=new ArrayList<>(); List<Accepted> accepted=new ArrayList<>();
        Set<String> keys=new HashSet<>();
        for(int k=0;k<request.keys().size();k++) {
            var key=request.keys().get(k);TraceQueryBuilder.validateColumnName(key.column());
            String label=key.column().substring(key.column().lastIndexOf('.')+1);
            if(!keys.add(label))throw fail("KEY_OUTPUT_DUPLICATE");
            int index=-1;for(int i=0;i<output.size();i++)if(label.equals(output.get(i).label()))index=i;
            if(index<0){issues.add(new ComplexSourcePlanService.Issue(null,k,"KEY_OUTPUT_MISSING"));continue;}
            var column=lineage.columns().get(index);
            if(column.errorReason()!=null){issues.add(new ComplexSourcePlanService.Issue(null,k,column.errorReason()));continue;}
            if(key.type()!=KeyValue.Type.DECIMAL){issues.add(new ComplexSourcePlanService.Issue(null,k,"ORACLE_SOURCE_NUMERIC_KEY_REQUIRED"));continue;}
            for(var binding:column.bindings()) {
                if(!(binding.expression() instanceof SqlIdentifier id)||id.names.size()!=2){issues.add(new ComplexSourcePlanService.Issue(binding.leaf().occurrence(),k,"DIRECT_COLUMN_REQUIRED"));continue;}
                var relation=binding.leaf().relation();Table table=tables.get(relation.schema()+"."+relation.name());
                String name=id.names.get(1);var type=table==null?null:table.numbers().get(name);var actual=output.get(index);
                if(type==null||!Set.of(Types.NUMERIC,Types.DECIMAL).contains(actual.type())||!type.signature().equals(column.certifiedType())) {
                    issues.add(new ComplexSourcePlanService.Issue(binding.leaf().occurrence(),k,"SOURCE_RESULT_TYPE_MISMATCH"));continue;
                }
                if(type.precision()!=null&&actual.precision()!=Integer.parseInt(type.precision())||type.scale()!=null&&actual.scale()!=Integer.parseInt(type.scale())) {
                    issues.add(new ComplexSourcePlanService.Issue(binding.leaf().occurrence(),k,"SOURCE_RESULT_TYPE_MISMATCH"));continue;
                }
                accepted.add(new Accepted(k,label,name,binding));
            }
        }
        List<ComplexSourcePlanService.Source> sources=new ArrayList<>();
        for(var leaf:lineage.leaves()) {
            var relation=leaf.relation();
            if(relation.errorReason()!=null){issues.add(new ComplexSourcePlanService.Issue(leaf.occurrence(),null,relation.errorReason(),relation.schema(),relation.name(),leaf.path()));continue;}
            Table table=tables.get(relation.schema()+"."+relation.name());
            var bindings=accepted.stream().filter(a->a.binding().leaf().equals(leaf)).toList();
            var boundColumns=bindings.stream().map(Accepted::column).toList();
            if(table==null||table.primaryKey().isEmpty()||!boundColumns.containsAll(table.primaryKey())) {
                issues.add(new ComplexSourcePlanService.Issue(leaf.occurrence(),null,"SOURCE_COMPLETE_PRIMARY_KEY_REQUIRED",relation.schema(),relation.name(),leaf.path()));continue;
            }
            List<String> projections=new ArrayList<>();List<PhysicalSourcePlanService.BoundKey> bound=new ArrayList<>();List<ComplexSourcePlanService.Mapping> mappings=new ArrayList<>();
            List<Output> expected=new ArrayList<>();
            for(var binding:bindings) {
                String label="source_key_"+binding.keyIndex(), expression=q("src")+"."+q(binding.column());
                projections.add(expression+" AS "+q(label));
                var key=request.keys().get(binding.keyIndex());bound.add(new PhysicalSourcePlanService.BoundKey(label,key.value(),key.type()));
                mappings.add(new ComplexSourcePlanService.Mapping(binding.keyIndex(),binding.output(),expression));
                expected.add(output.stream().filter(o->o.label().equals(binding.output())).findFirst().orElseThrow());
            }
            String sql="SELECT "+String.join(",",projections)+" FROM "+q(relation.schema())+"."+q(relation.name())+" "+q("src");
            var physical=metadata(connection,task,sql);
            boolean valid=physical.size()==expected.size();
            for(int i=0;valid&&i<physical.size();i++) {
                var a=physical.get(i);var e=expected.get(i);
                valid=a.label().equals(bound.get(i).column())&&a.type()==e.type()&&a.precision()==e.precision()&&a.scale()==e.scale();
            }
            if(!valid){issues.add(new ComplexSourcePlanService.Issue(leaf.occurrence(),null,"PHYSICAL_TYPE_UNVERIFIED",relation.schema(),relation.name(),leaf.path()));continue;}
            sources.add(new ComplexSourcePlanService.Source(leaf.occurrence(),relation.schema(),relation.name(),leaf.path(),sql,List.copyOf(bound),List.copyOf(mappings)));
        }
        task.checkpoint();
        return new ComplexSourcePlanService.Plan(sources.isEmpty()?"UNKNOWN":issues.isEmpty()?"COMPLETED":"PARTIAL",!sources.isEmpty()&&issues.isEmpty(),issues.isEmpty()?null:"SOURCE_COVERAGE_INCOMPLETE",request.connectionId(),request.sql(),metadataSql,List.copyOf(sources),List.copyOf(issues),Instant.now().toString());
    }
}
