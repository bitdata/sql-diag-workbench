package com.bitdata.sqlworkbench.diagnostics.source;

import com.bitdata.sqlworkbench.connection.*;
import com.bitdata.sqlworkbench.diagnostics.trace.*;
import com.bitdata.sqlworkbench.sql.*;
import org.apache.calcite.avatica.util.Casing;
import org.apache.calcite.sql.*;
import org.apache.calcite.sql.parser.*;
import org.springframework.stereotype.Service;
import java.sql.*;
import java.util.*;
import java.util.concurrent.ForkJoinPool;

/** Resolves individual source observations; never produces an all-sources absence verdict. */
@Service
public class ComplexSourcePlanService {
    public record Mapping(int keyIndex,String outputColumn,String sourceExpression) {}
    public record Source(String occurrence,String schema,String relation,List<String> path,String sourceSql,
                         List<PhysicalSourcePlanService.BoundKey> keys,List<Mapping> mappings) {}
    public record Issue(String occurrence,Integer keyIndex,String errorReason,String schema,String relation,List<String> path) {
        Issue(String occurrence,Integer keyIndex,String errorReason) {this(occurrence,keyIndex,errorReason,null,null,List.of());}
    }
    public record Plan(String status,boolean verified,String errorReason,String connectionId,String originalSql,
                       String metadataSql,List<Source> sources,List<Issue> issues,String observedAt) {}
    private final ConnectionService connections;
    private final DatabaseStrategyFactory strategies;
    private final SqlDialectResolver resolver;
    private final SelectOnlyGuard guard;
    public ComplexSourcePlanService(ConnectionService connections,DatabaseStrategyFactory strategies,
                                    SqlDialectResolver resolver,SelectOnlyGuard guard) {
        this.connections=connections;this.strategies=strategies;this.resolver=resolver;this.guard=guard;
    }
    public Plan inspect(PhysicalSourcePlanService.Request request) {
        String metadata=null;
        try {
            if(request==null||request.connectionId()==null||request.keys()==null||request.keys().isEmpty()||request.keys().size()>16||request.keys().stream().anyMatch(Objects::isNull))throw fail("SOURCE_INPUT_INVALID");
            var config=connections.getById(request.connectionId()).orElseThrow(()->fail("SOURCE_CONNECTION_UNAVAILABLE"));
            String dialect=config.dialect();if(!Set.of("postgresql","mysql").contains(dialect))throw fail("SOURCE_DIALECT_UNSUPPORTED");
            guard.requireSingleSelect(request.sql(),dialect);
            var parser=resolver.parserConfig(dialect).withUnquotedCasing(dialect.equals("postgresql")?Casing.TO_LOWER:Casing.UNCHANGED);
            SqlNode root=SqlParser.create(request.sql(),parser).parseQuery();
            metadata=zero(root).toSqlString(resolver.getDialect(dialect)).getSql();
            var strategy=strategies.get(dialect);
            try(Connection c=connections.openJdbcConnection(request.connectionId())) {
                Deadline deadline=new Deadline(c);
                deadline.installNetworkTimeout();
                strategy.configureReadOnly(c);
                deadline.check();
                SourceLineageResolver.Catalog catalog=(name,schema)->read(c,dialect,name,schema,deadline);
                var lineage=new SourceLineageResolver(dialect,resolver,catalog).resolve(request.sql());
                List<String> labels;
                try(Statement s=c.createStatement()) {
                    deadline.configure(s);
                    try(ResultSet rows=s.executeQuery(metadata)) {
                        var m=rows.getMetaData();var match=TraceKeyProjection.match(m,request.keys(),strategy.getDialectProfile());
                        if(match.reason()!=null)throw fail(match.reason());
                        if(m.getColumnCount()!=lineage.columns().size())throw fail("SOURCE_OUTPUT_ARITY_MISMATCH");
                        labels=match.labels();
                        for(int i=0;i<m.getColumnCount();i++) {
                            String expected=lineage.columns().get(i).name();
                            // Unaliased expressions have driver-specific labels. Such keys need an explicit alias.
                            if(!expected.isEmpty()&&!PhysicalSourcePlanService.same(expected,m.getColumnLabel(i+1),dialect))throw fail("SOURCE_OUTPUT_LABEL_MISMATCH");
                        }
                    }
                }
                Map<String,List<Mapping>> mappings=new LinkedHashMap<>();
                Map<String,List<PhysicalSourcePlanService.BoundKey>> bound=new LinkedHashMap<>();
                List<Issue> issues=new ArrayList<>();
                for(int k=0;k<request.keys().size();k++) {
                    String label=labels.get(k);var matches=lineage.columns().stream().filter(column->PhysicalSourcePlanService.same(column.name(),label,dialect)).toList();
                    if(matches.size()!=1) {issues.add(new Issue(null,k,"SOURCE_OUTPUT_LABEL_UNAVAILABLE"));continue;}
                    var column=matches.get(0);
                    if(column.errorReason()!=null) {issues.add(new Issue(null,k,column.errorReason()));continue;}
                    for(var binding:column.bindings()) {
                        String id=binding.leaf().occurrence(),expression=binding.expression().toSqlString(resolver.getDialect(dialect)).getSql();
                        mappings.computeIfAbsent(id,ignored->new ArrayList<>()).add(new Mapping(k,label,expression));
                        var key=request.keys().get(k);
                        bound.computeIfAbsent(id,ignored->new ArrayList<>()).add(new PhysicalSourcePlanService.BoundKey("source_key_"+k,key.value(),key.type()));
                    }
                }
                List<Source> sources=new ArrayList<>();char quote=strategy.getDialectProfile().quoteChar();
                for(var leaf:lineage.leaves()) {
                    var map=mappings.get(leaf.occurrence());
                    if(map==null) {issues.add(new Issue(leaf.occurrence(),null,"SOURCE_RELATION_WITHOUT_KEY",leaf.relation().schema(),leaf.relation().name(),leaf.path()));continue;}
                    var relation=leaf.relation();List<String> select=new ArrayList<>();
                    for(Mapping m:map)select.add(m.sourceExpression()+" AS "+PhysicalSourcePlanService.quote("source_key_"+m.keyIndex(),quote));
                    String sql="SELECT "+String.join(", ",select)+" FROM "+PhysicalSourcePlanService.quote(relation.schema(),quote)+"."+PhysicalSourcePlanService.quote(relation.name(),quote)+" AS "+PhysicalSourcePlanService.quote("src",quote);
                    guard.requireSingleSelect(sql,dialect);
                    sources.add(new Source(leaf.occurrence(),relation.schema(),relation.name(),leaf.path(),sql,List.copyOf(bound.get(leaf.occurrence())),List.copyOf(map)));
                }
                deadline.check();
                String status=sources.isEmpty()?"UNKNOWN":issues.isEmpty()?"COMPLETED":"PARTIAL";
                return new Plan(status,issues.isEmpty()&&!sources.isEmpty(),issues.isEmpty()?null:"SOURCE_COVERAGE_INCOMPLETE",request.connectionId(),request.sql(),metadata,List.copyOf(sources),List.copyOf(issues),java.time.Instant.now().toString());
            }
        } catch(PhysicalSourcePlanService.Unverified e) {return unknown(request,metadata,e.code);}
        catch(CredentialFailure e) {return unknown(request,metadata,e.code);}
        catch(SQLTimeoutException e) {return unknown(request,metadata,"SOURCE_METADATA_TIMEOUT");}
        catch(SQLException e) {return unknown(request,metadata,"SOURCE_METADATA_FAILED");}
        catch(SqlParseException|IllegalArgumentException e) {return unknown(request,metadata,"SOURCE_SQL_UNSUPPORTED");}
        catch(RuntimeException e) {return unknown(request,metadata,"SOURCE_PREPARATION_FAILED");}
    }
    private SourceLineageResolver.Relation read(Connection c,String dialect,List<String> name,String defaultSchema,Deadline deadline) throws SQLException {
        String table=name.get(name.size()-1),schema=name.size()==2?name.get(0):defaultSchema;
        String actualSchema,actualTable,kind,definition;
        String sql;
        if(dialect.equals("postgresql")) {
            sql="SELECT n.nspname,c.relname,c.relkind,CASE WHEN c.relkind='v' THEN pg_catalog.pg_get_viewdef(c.oid,true) ELSE NULL END FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace WHERE c.oid=pg_catalog.to_regclass(?)";
        } else {
            if(schema==null)schema=c.getCatalog();
            sql="SELECT t.TABLE_SCHEMA,t.TABLE_NAME,t.TABLE_TYPE,v.VIEW_DEFINITION FROM information_schema.tables t LEFT JOIN information_schema.views v ON v.TABLE_SCHEMA=t.TABLE_SCHEMA AND v.TABLE_NAME=t.TABLE_NAME WHERE t.TABLE_SCHEMA=? AND t.TABLE_NAME=?";
        }
        try(PreparedStatement s=c.prepareStatement(sql)) {
            deadline.configure(s);
            if(dialect.equals("postgresql"))s.setString(1,(schema==null?"":PhysicalSourcePlanService.quote(schema,'"')+".")+PhysicalSourcePlanService.quote(table,'"'));
            else {s.setString(1,schema);s.setString(2,table);}
            try(ResultSet rows=s.executeQuery()) {
                if(!rows.next())throw fail("SOURCE_RELATION_UNAVAILABLE");
                actualSchema=rows.getString(1);actualTable=rows.getString(2);kind=rows.getString(3);definition=rows.getString(4);
                if(rows.next())throw fail("SOURCE_RELATION_AMBIGUOUS");
            }
        }
        boolean view=dialect.equals("postgresql")?"v".equals(kind):"VIEW".equals(kind);
        if(view&&(definition==null||definition.isBlank()))throw fail("SOURCE_VIEW_DEFINITION_UNAVAILABLE");
        if(!view&&!(dialect.equals("postgresql")?Set.of("r","p").contains(kind):"BASE TABLE".equals(kind)))throw fail("SOURCE_NOT_PHYSICAL_TABLE");
        char quote=strategies.get(dialect).getDialectProfile().quoteChar();
        String probe="SELECT * FROM "+PhysicalSourcePlanService.quote(actualSchema,quote)+"."+PhysicalSourcePlanService.quote(actualTable,quote)+" LIMIT 0";
        List<String> columns=new ArrayList<>();
        try(Statement s=c.createStatement()) {
            deadline.configure(s);
            try(ResultSet rows=s.executeQuery(probe)) {
                var meta=rows.getMetaData();
                for(int i=1;i<=meta.getColumnCount();i++) {
                    String column=meta.getColumnLabel(i);columns.add(column);
                    if(!view) {
                        String[] origin=PhysicalSourcePlanService.origin(meta,i,dialect);
                        if(!actualSchema.equals(origin[0])||!actualTable.equals(origin[1])||!column.equals(origin[2]))throw fail("SOURCE_ORIGIN_MISMATCH");
                    }
                }
            }
        }
        Map<String,String> types=new LinkedHashMap<>();
        if(!view) {
            // JDBC type names omit PostgreSQL typmods/domains and SQL collation. Obtain the
            // complete declaration from the same connection/catalog used by the origin probe.
            String typeSql=dialect.equals("postgresql")?
                "SELECT a.attname,a.atttypid::text || ':' || a.atttypmod::text || ':' || a.attcollation::text FROM pg_catalog.pg_attribute a JOIN pg_catalog.pg_class c ON c.oid=a.attrelid JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname=? AND c.relname=? AND a.attnum>0 AND NOT a.attisdropped":
                "SELECT COLUMN_NAME,CONCAT(COLUMN_TYPE,':',COALESCE(CHARACTER_SET_NAME,''),':',COALESCE(COLLATION_NAME,'')) FROM information_schema.columns WHERE TABLE_SCHEMA=? AND TABLE_NAME=?";
            try(PreparedStatement s=c.prepareStatement(typeSql)) {
                deadline.configure(s);s.setString(1,actualSchema);s.setString(2,actualTable);
                try(ResultSet rows=s.executeQuery()) {
                    while(rows.next()) {String type=rows.getString(2);if(type!=null)types.put(rows.getString(1),type);}
                }
            }
        }
        return new SourceLineageResolver.Relation(actualSchema,actualTable,columns,view?definition:null,types);
    }
    static SqlNode zero(SqlNode root) {
        if(root instanceof SqlWith with) {with.body=zero(with.body);return with;}
        SqlNode query=root instanceof SqlOrderBy order?order.query:root;
        if(query instanceof SqlSelect select) {select.setOffset(null);select.setFetch(null);}
        return new SqlOrderBy(SqlParserPos.ZERO,query,root instanceof SqlOrderBy order?order.orderList:SqlNodeList.EMPTY,null,SqlLiteral.createExactNumeric("0",SqlParserPos.ZERO));
    }
    private static Plan unknown(PhysicalSourcePlanService.Request request,String metadata,String reason) {
        return new Plan("UNKNOWN",false,reason,request==null?null:request.connectionId(),request==null?null:request.sql(),metadata,List.of(),List.of(new Issue(null,null,reason)),java.time.Instant.now().toString());
    }
    private static PhysicalSourcePlanService.Unverified fail(String code) {return new PhysicalSourcePlanService.Unverified(code);}
    private static final class Deadline {
        final Connection connection;final long expires=System.nanoTime()+5_000_000_000L;
        Deadline(Connection connection) {this.connection=connection;}
        int remaining() throws SQLTimeoutException {long ms=(expires-System.nanoTime())/1_000_000;if(ms<=0)throw new SQLTimeoutException("SOURCE_METADATA_TIMEOUT");return (int)ms;}
        void check() throws SQLTimeoutException {remaining();}
        void installNetworkTimeout() throws SQLException {connection.setNetworkTimeout(ForkJoinPool.commonPool(),remaining());}
        void configure(Statement s) throws SQLException {int ms=remaining();connection.setNetworkTimeout(ForkJoinPool.commonPool(),ms);s.setQueryTimeout(Math.max(1,(ms+999)/1000));}
    }
}
