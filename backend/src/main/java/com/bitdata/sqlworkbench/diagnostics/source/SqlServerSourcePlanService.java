package com.bitdata.sqlworkbench.diagnostics.source;

import com.bitdata.sqlworkbench.connection.*;
import com.bitdata.sqlworkbench.diagnostics.trace.*;
import com.bitdata.sqlworkbench.sql.*;
import org.apache.calcite.sql.*;
import org.springframework.stereotype.Service;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** SQLServer direct-column certification. Unverified outputs never become source absence facts. */
@Service
public final class SqlServerSourcePlanService {
    private final ConnectionService connections;
    private final SqlDialectResolver resolver;
    private final SelectOnlyGuard guard;
    public SqlServerSourcePlanService(ConnectionService connections,SqlDialectResolver resolver,SelectOnlyGuard guard) {
        this.connections=connections;this.resolver=resolver;this.guard=guard;
    }
    private record Type(String schema,String declared,String base,int length,int precision,int scale,String collation) {}
    private record Table(String schema,String name,Map<String,Type> types) {}
    private record Accepted(int keyIndex,String label,SourceLineageResolver.Binding binding,Type type) {}
    private static final Set<String> TYPES=Set.of("tinyint","smallint","int","bigint","decimal","numeric","bit","money","smallmoney","char","varchar","nchar","nvarchar");
    private static String q(String s){return SqlServerObservationSql.quote(s);}
    private static PhysicalSourcePlanService.Unverified fail(String code){return new PhysicalSourcePlanService.Unverified(code);}
    private static int number(Map<String,Object> m,String key){return ((Number)m.get(key)).intValue();}
    private static boolean typeMatches(Type t,Map<String,Object> d) {
        String system=(String)d.get("system_type_name");
        boolean aliases=t.declared().equals(t.base())||Objects.equals(t.declared(),d.get("user_type_name"))&&Objects.equals(t.schema(),d.get("user_type_schema"));
        return TYPES.contains(t.base())&&t.length()>=0&&aliases&&system!=null&&(system.equals(t.base())||system.startsWith(t.base()+"("))
            &&t.length()==number(d,"max_length")&&t.precision()==number(d,"precision")&&t.scale()==number(d,"scale")&&Objects.equals(t.collation(),d.get("collation_name"));
    }
    private static final class Deadline {
        final long expires=System.nanoTime()+5_000_000_000L;
        int remaining()throws SQLTimeoutException {long ms=(expires-System.nanoTime())/1_000_000L;if(ms<=0)throw new SQLTimeoutException("SOURCE_METADATA_TIMEOUT");return (int)ms;}
        void configure(Connection c,Statement s)throws SQLException {int ms=remaining();c.setNetworkTimeout(java.util.concurrent.ForkJoinPool.commonPool(),ms);s.setQueryTimeout(Math.max(1,(ms+999)/1000));}
    }
    private List<Map<String,Object>> query(Connection c,Deadline deadline,String sql,String... values)throws SQLException {
        var result=new ArrayList<Map<String,Object>>();
        try(var s=c.prepareStatement(sql)){
            deadline.configure(c,s);for(int i=0;i<values.length;i++)s.setNString(i+1,values[i]);
            try(var rs=s.executeQuery()){var m=rs.getMetaData();while(rs.next()){var r=new LinkedHashMap<String,Object>();for(int i=1;i<=m.getColumnCount();i++)r.put(m.getColumnLabel(i),rs.getObject(i));result.add(r);}}
        }deadline.remaining();return result;
    }
    private List<Map<String,Object>> describe(Connection c,Deadline deadline,String sql)throws SQLException {
        var rows=query(c,deadline,"SELECT * FROM sys.dm_exec_describe_first_result_set(?,NULL,1)",sql);
        if(rows.stream().anyMatch(r->r.get("error_number")!=null))throw fail("SOURCE_DESCRIPTION_UNAVAILABLE");
        return rows.stream().filter(r->!Boolean.TRUE.equals(r.get("is_hidden"))).toList();
    }
    private Table read(Connection c,Deadline deadline,List<String> names)throws SQLException {
        if(names.size()!=2)throw fail("EXPLICIT_SCHEMA_REQUIRED");
        String reference=names.stream().map(SqlServerSourcePlanService::q).collect(java.util.stream.Collectors.joining("."));
        var objects=query(c,deadline,"SELECT SCHEMA_NAME(schema_id) s,name,type FROM sys.objects WHERE object_id=OBJECT_ID(?)",reference);
        if(objects.size()!=1)throw fail("OBJECT_NOT_VISIBLE_OR_MISSING");
        var o=objects.get(0);String schema=(String)o.get("s"),name=(String)o.get("name");
        if(!"U".equals(((String)o.get("type")).trim()))throw fail("SOURCE_NOT_PHYSICAL_TABLE");
        if(!schema.equals(names.get(0))||!name.equals(names.get(1)))throw fail("SOURCE_NAME_COMPARISON_UNVERIFIED");
        var cols=query(c,deadline,"SELECT c.name,SCHEMA_NAME(ut.schema_id) type_schema,ut.name declared_type,bt.name base_type,c.max_length,c.precision,c.scale,c.collation_name FROM sys.columns c JOIN sys.types ut ON ut.user_type_id=c.user_type_id JOIN sys.types bt ON bt.user_type_id=c.system_type_id AND bt.user_type_id=bt.system_type_id WHERE c.object_id=OBJECT_ID(?) ORDER BY c.column_id",reference);
        var types=new LinkedHashMap<String,Type>();
        for(var col:cols)types.put((String)col.get("name"),new Type((String)col.get("type_schema"),(String)col.get("declared_type"),(String)col.get("base_type"),number(col,"max_length"),number(col,"precision"),number(col,"scale"),(String)col.get("collation_name")));
        return new Table(schema,name,Collections.unmodifiableMap(new LinkedHashMap<>(types)));
    }
    public PhysicalSourcePlanService.Plan inspectDirect(PhysicalSourcePlanService.Request request) {
        var plan=inspect(request,true);
        if(!plan.verified()||plan.sources().size()!=1)return new PhysicalSourcePlanService.Plan("UNKNOWN",false,plan.errorReason()==null?"DIRECT_PHYSICAL_SOURCE_REQUIRED":plan.errorReason(),request.connectionId(),request.sql(),plan.metadataSql(),null,null,null,List.of(),plan.observedAt());
        var s=plan.sources().get(0);
        return new PhysicalSourcePlanService.Plan("COMPLETED",true,null,request.connectionId(),request.sql(),plan.metadataSql(),s.schema(),s.relation(),s.sourceSql(),s.keys(),plan.observedAt());
    }
    public ComplexSourcePlanService.Plan inspect(PhysicalSourcePlanService.Request request,boolean direct) {
        String metadata=null;List<ComplexSourcePlanService.Source> sources=new ArrayList<>();List<ComplexSourcePlanService.Issue> issues=new ArrayList<>();
        try {
            if(request==null||request.keys()==null||request.keys().isEmpty()||request.keys().size()>16||request.keys().stream().anyMatch(Objects::isNull))throw fail("SOURCE_INPUT_INVALID");
            if(request.keys().stream().anyMatch(k->k.type()==null))throw fail("TYPED_KEY_REQUIRED");
            guard.requireSingleSelect(request.sql(),"sqlserver");
            SqlNode root=org.apache.calcite.sql.parser.SqlParser.create(request.sql(),resolver.parserConfig("sqlserver")).parseQuery();
            if(direct){SqlNode body=root instanceof SqlOrderBy order?order.query:root;if(!(body instanceof SqlSelect select))throw fail("DIRECT_PHYSICAL_SOURCE_REQUIRED");SqlNode from=select.getFrom();if(from instanceof SqlCall a&&a.getKind()==SqlKind.AS)from=a.operand(0);if(!(from instanceof SqlIdentifier))throw fail("DIRECT_PHYSICAL_SOURCE_REQUIRED");}
            metadata=SqlServerObservationSql.build(request.sql(),List.of(),"METADATA").sql();
            Deadline deadline=new Deadline();
            var config=connections.getById(request.connectionId()).orElseThrow(()->fail("SOURCE_CONNECTION_UNAVAILABLE"));
            try(Connection c=connections.openJdbcConnectionWithTimeout(request.connectionId(),ConnectionIdentity.revision(config),deadline.remaining())) {
                var tables=new HashMap<String,Table>();
                var lineage=new SourceLineageResolver("sqlserver",resolver,(names,ignored)->{
                    Table t=read(c,deadline,names);tables.put(t.schema()+"."+t.name(),t);
                    var types=new LinkedHashMap<String,String>();t.types().forEach((name,type)->types.put(name,type.toString()));
                    return new SourceLineageResolver.Relation(t.schema(),t.name(),new ArrayList<>(t.types().keySet()),null,types);
                }).resolve(request.sql());
                var output=describe(c,deadline,request.sql());
                if(output.size()!=lineage.columns().size())throw fail("SOURCE_OUTPUT_ARITY_MISMATCH");
                Set<String> unique=new HashSet<>();for(var d:output)if(d.get("name")==null||!unique.add((String)d.get("name")))throw fail("OUTPUT_LABEL_AMBIGUOUS");
                Map<Integer,String> jdbcTypes=new HashMap<>();Map<Integer,Integer> jdbcPrecision=new HashMap<>(),jdbcScale=new HashMap<>();
                try(var statement=c.prepareStatement(metadata)) {
                    deadline.configure(c,statement);try(var rows=statement.executeQuery()) {
                        var match=TraceKeyProjection.match(rows.getMetaData(),request.keys(),resolver.getProfile("sqlserver"));
                        if(match.reason()!=null)throw fail(match.reason());
                        for(int i=0;i<output.size();i++) {
                            var m=rows.getMetaData();if(!Objects.equals(output.get(i).get("name"),m.getColumnLabel(i+1)))throw fail("SOURCE_OUTPUT_LABEL_MISMATCH");
                            jdbcTypes.put(i,m.getColumnTypeName(i+1));jdbcPrecision.put(i,m.getPrecision(i+1));jdbcScale.put(i,m.getScale(i+1));
                        }
                    }
                }
                var accepted=new ArrayList<Accepted>();
                for(int k=0;k<request.keys().size();k++) {
                    String label=request.keys().get(k).column();label=label.substring(label.lastIndexOf('.')+1);
                    int index=-1;for(int i=0;i<output.size();i++)if(label.equals(output.get(i).get("name")))index=i;
                    if(index<0){issues.add(new ComplexSourcePlanService.Issue(null,k,"KEY_OUTPUT_MISSING"));continue;}
                    var column=lineage.columns().get(index);var description=output.get(index);
                    if(column.errorReason()!=null){issues.add(new ComplexSourcePlanService.Issue(null,k,column.errorReason()));continue;}
                    for(var binding:column.bindings()) {
                        if(!(binding.expression() instanceof SqlIdentifier id)||id.names.size()!=2){issues.add(new ComplexSourcePlanService.Issue(binding.leaf().occurrence(),k,"DIRECT_COLUMN_REQUIRED"));continue;}
                        var rel=binding.leaf().relation();Type type=tables.get(rel.schema()+"."+rel.name()).types().get(id.names.get(1));
                        int capacity=type==null?-1:Set.of("nvarchar","nchar").contains(type.base())?type.length()/2:Set.of("varchar","char").contains(type.base())?type.length():type.precision();
                        if(type==null||!typeMatches(type,description)||!type.base().equalsIgnoreCase(jdbcTypes.get(index))
                            ||capacity!=jdbcPrecision.get(index)||type.scale()!=jdbcScale.get(index)){
                            issues.add(new ComplexSourcePlanService.Issue(binding.leaf().occurrence(),k,"SOURCE_RESULT_TYPE_MISMATCH"));continue;}
                        if(description.get("source_column")!=null&&(!Objects.equals(description.get("source_column"),id.names.get(1))||!Objects.equals(description.get("source_schema"),rel.schema())||!Objects.equals(description.get("source_table"),rel.name()))){issues.add(new ComplexSourcePlanService.Issue(binding.leaf().occurrence(),k,"SOURCE_ORIGIN_MISMATCH"));continue;}
                        accepted.add(new Accepted(k,label,binding,type));
                    }
                }
                for(var leaf:lineage.leaves()) {
                    if(leaf.relation().errorReason()!=null){issues.add(new ComplexSourcePlanService.Issue(leaf.occurrence(),null,leaf.relation().errorReason(),leaf.relation().schema(),leaf.relation().name(),leaf.path()));continue;}
                    var bindings=accepted.stream().filter(a->a.binding().leaf().equals(leaf)).toList();
                    if(bindings.isEmpty()){issues.add(new ComplexSourcePlanService.Issue(leaf.occurrence(),null,"SOURCE_RELATION_WITHOUT_KEY",leaf.relation().schema(),leaf.relation().name(),leaf.path()));continue;}
                    var projections=new ArrayList<String>();var keys=new ArrayList<PhysicalSourcePlanService.BoundKey>();var mappings=new ArrayList<ComplexSourcePlanService.Mapping>();
                    for(var a:bindings){String expression=NodeToSql.toQuotedSql(a.binding().expression(),resolver.getProfile("sqlserver"));String label="source_key_"+a.keyIndex();projections.add(expression+" AS "+q(label));var key=request.keys().get(a.keyIndex());keys.add(new PhysicalSourcePlanService.BoundKey(label,key.value(),key.type()));mappings.add(new ComplexSourcePlanService.Mapping(a.keyIndex(),a.label(),expression));}
                    String sql="SELECT "+String.join(",",projections)+" FROM "+q(leaf.relation().schema())+"."+q(leaf.relation().name())+" AS [src]";
                    var described=describe(c,deadline,sql);
                    if(described.size()!=bindings.size())throw fail("PHYSICAL_TYPE_UNVERIFIED");
                    boolean valid=true;for(int i=0;i<bindings.size();i++)valid&=typeMatches(bindings.get(i).type(),described.get(i));
                    if(!valid){issues.add(new ComplexSourcePlanService.Issue(leaf.occurrence(),null,"PHYSICAL_TYPE_UNVERIFIED"));continue;}
                    sources.add(new ComplexSourcePlanService.Source(leaf.occurrence(),leaf.relation().schema(),leaf.relation().name(),leaf.path(),sql,List.copyOf(keys),List.copyOf(mappings)));
                }
                deadline.remaining();
            }
        }catch(PhysicalSourcePlanService.Unverified e){issues.add(new ComplexSourcePlanService.Issue(null,null,e.code));}
        catch(CredentialFailure e){issues.add(new ComplexSourcePlanService.Issue(null,null,e.code));}
        catch(WorkloadBusyException e){issues.add(new ComplexSourcePlanService.Issue(null,null,e.errorReason()));}
        catch(ConnectTimeoutException e){issues.add(new ComplexSourcePlanService.Issue(null,null,"SOURCE_CONNECTION_TIMEOUT"));}
        catch(SQLTimeoutException e){issues.add(new ComplexSourcePlanService.Issue(null,null,"SOURCE_METADATA_TIMEOUT"));}
        catch(SQLException e){issues.add(new ComplexSourcePlanService.Issue(null,null,e.getSQLState()!=null&&e.getSQLState().startsWith("08")?"SOURCE_CONNECTION_FAILED":"SOURCE_METADATA_FAILED"));}
        catch(SqlSerializationException e){issues.add(new ComplexSourcePlanService.Issue(null,null,e.reason()));}
        catch(Exception e){issues.add(new ComplexSourcePlanService.Issue(null,null,"SOURCE_PREPARATION_FAILED"));}
        String status=sources.isEmpty()?"UNKNOWN":issues.isEmpty()?"COMPLETED":"PARTIAL";
        return new ComplexSourcePlanService.Plan(status,issues.isEmpty()&&!sources.isEmpty(),issues.isEmpty()?null:"SOURCE_COVERAGE_INCOMPLETE",request==null?null:request.connectionId(),request==null?null:request.sql(),metadata,List.copyOf(sources),List.copyOf(issues),Instant.now().toString());
    }
}
