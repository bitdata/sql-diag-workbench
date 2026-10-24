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

/** Certifies one directly projected physical source. Never infers lineage through expressions. */
@Service
public class PhysicalSourcePlanService {
    public record Request(String connectionId, String sql, List<KeyValue> keys) {}
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
    public record BoundKey(String column,Object value,KeyValue.Type type) {}
    public record Plan(String status, boolean verified, String errorReason, String connectionId,
        String originalSql, String metadataSql, String schema, String relation, String sourceSql,
        List<BoundKey> keys, String observedAt) {}
    private final ConnectionService connections;
    private final DatabaseStrategyFactory strategies;
    private final SqlDialectResolver resolver;
    private final SelectOnlyGuard guard;
    public PhysicalSourcePlanService(ConnectionService connections, DatabaseStrategyFactory strategies,
                                    SqlDialectResolver resolver, SelectOnlyGuard guard) {
        this.connections=connections; this.strategies=strategies; this.resolver=resolver; this.guard=guard;
    }
    static final class Unverified extends RuntimeException {
        final String code;
        Unverified(String code) { super(code); this.code=code; }
    }
    public Plan inspect(Request request) {
        String metadataSql=null;
        try {
            if(request==null || request.connectionId()==null || request.keys()==null || request.keys().isEmpty() || request.keys().size()>16 || request.keys().stream().anyMatch(Objects::isNull))
                throw new Unverified("SOURCE_INPUT_INVALID");
            var config=connections.getById(request.connectionId()).orElseThrow(()->new Unverified("SOURCE_CONNECTION_UNAVAILABLE"));
            String dialect=config.dialect();
            if(!Set.of("postgresql","mysql").contains(dialect)) throw new Unverified("SOURCE_DIALECT_UNSUPPORTED");
            guard.requireSingleSelect(request.sql(),dialect);
            var shape=shape(request.sql(),dialect,resolver);
            metadataSql=shape.metadataSql();
            var strategy=strategies.get(dialect);
            try(Connection c=connections.openJdbcConnection(request.connectionId())) {
                c.setNetworkTimeout(ForkJoinPool.commonPool(),5000);
                strategy.configureReadOnly(c);
                try(Statement statement=c.createStatement()) {
                    statement.setQueryTimeout(5);
                    try(ResultSet rows=statement.executeQuery(metadataSql)) {
                        var meta=rows.getMetaData();
                        var match=TraceKeyProjection.match(meta,request.keys(),strategy.getDialectProfile());
                        if(match.reason()!=null) throw new Unverified(match.reason());
                        List<BoundKey> mapped=new ArrayList<>(); String schema=null,table=null;
                        for(int k=0;k<request.keys().size();k++) {
                            String label=match.labels().get(k); int index=0;
                            for(int i=1;i<=meta.getColumnCount();i++) if(label.equals(meta.getColumnLabel(i))) { index=i;break; }
                            String[] origin=origin(meta,index,dialect);
                            if(origin[0].isEmpty() || origin[1].isEmpty() || origin[2].isEmpty()) throw new Unverified("SOURCE_ORIGIN_UNAVAILABLE");
                            if(!shape.accepts(label,origin[2],dialect) || !shape.table().equals(origin[1]) ||
                                shape.schema()!=null && !shape.schema().equals(origin[0])) throw new Unverified("SOURCE_ORIGIN_MISMATCH");
                            if(table!=null && (!table.equals(origin[1]) || !schema.equals(origin[0]))) throw new Unverified("MULTIPLE_SOURCE_TABLES");
                            schema=origin[0];table=origin[1];
                            if(!origin[2].matches("[A-Za-z_][A-Za-z0-9_]{0,62}")) throw new Unverified("SOURCE_COLUMN_UNSUPPORTED");
                            var key=request.keys().get(k); mapped.add(new BoundKey(origin[2],key.value(),key.type()));
                        }
                        if(!physical(c,dialect,schema,table)) throw new Unverified("SOURCE_NOT_PHYSICAL_TABLE");
                        char quote=strategy.getDialectProfile().quoteChar();
                        String source="SELECT * FROM "+quote(schema,quote)+"."+quote(table,quote);
                        return new Plan("COMPLETED",true,null,request.connectionId(),request.sql(),metadataSql,schema,table,source,List.copyOf(mapped),java.time.Instant.now().toString());
                    }
                }
            }
        } catch(Unverified e) { return unknown(request,metadataSql,e.code); }
        catch(CredentialFailure e) { return unknown(request,metadataSql,e.code); }
        catch(SQLTimeoutException e) { return unknown(request,metadataSql,"SOURCE_METADATA_TIMEOUT"); }
        catch(SQLException e) { return unknown(request,metadataSql,"SOURCE_METADATA_FAILED"); }
        catch(SqlParseException | IllegalArgumentException e) { return unknown(request,metadataSql,"SOURCE_SQL_UNSUPPORTED"); }
        catch(RuntimeException e) { return unknown(request,metadataSql,"SOURCE_PREPARATION_FAILED"); }
    }
    private static Plan unknown(Request r,String metadataSql,String reason) {
        return new Plan("UNKNOWN",false,reason,r==null?null:r.connectionId(),r==null?null:r.sql(),metadataSql,null,null,null,List.of(),java.time.Instant.now().toString());
    }
    static String quote(String s,char quote) {return quote+s.replace(""+quote,""+quote+quote)+quote;}
    static String[] origin(ResultSetMetaData meta,int index,String dialect) throws SQLException {
        String schema,table,column;
        if(dialect.equals("postgresql") && meta instanceof org.postgresql.PGResultSetMetaData pg) {
            schema=pg.getBaseSchemaName(index);table=pg.getBaseTableName(index);column=pg.getBaseColumnName(index);
        } else if(dialect.equals("mysql") && meta instanceof com.mysql.cj.jdbc.result.ResultSetMetaData mysql) {
            var field=mysql.getFields()[index-1];schema=field.getDatabaseName();table=field.getOriginalTableName();column=field.getOriginalName();
        } else throw new Unverified("SOURCE_DRIVER_UNSUPPORTED");
        return new String[]{schema==null?"":schema,table==null?"":table,column==null?"":column};
    }
    private static boolean physical(Connection c,String dialect,String schema,String table) throws SQLException {
        String sql=dialect.equals("postgresql")?
            "SELECT c.relkind FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname=? AND c.relname=?":
            "SELECT TABLE_TYPE FROM information_schema.tables WHERE TABLE_SCHEMA=? AND TABLE_NAME=?";
        try(PreparedStatement s=c.prepareStatement(sql)) {
            s.setQueryTimeout(5);s.setString(1,schema);s.setString(2,table);
            try(ResultSet r=s.executeQuery()) {if(!r.next()) return false;String kind=r.getString(1);return (dialect.equals("postgresql")?Set.of("r","p").contains(kind):"BASE TABLE".equals(kind))&&!r.next();}
        }
    }
    record Projection(String label,String column) {}
    record Shape(String table,String schema,List<Projection> projections,boolean star,String metadataSql) {
        boolean accepts(String label,String column,String dialect) {
            return projections.stream().anyMatch(p->same(p.label(),label,dialect)&&p.column().equals(column)) || star&&same(label,column,dialect);
        }
    }
    static boolean same(String a,String b,String dialect) {return dialect.equals("mysql")?a.equalsIgnoreCase(b):a.equals(b);}
    static Shape shape(String sql,String dialect,SqlDialectResolver resolver) throws SqlParseException {
        // PostgreSQL folds unquoted names to lower case on the server; quoted spelling stays intact.
        var config=resolver.parserConfig(dialect).withUnquotedCasing(dialect.equals("postgresql")?Casing.TO_LOWER:Casing.UNCHANGED);
        SqlNode root=SqlParser.create(sql,config).parseQuery();
        SqlNode query=root instanceof SqlOrderBy order?order.query:root;
        if(!(query instanceof SqlSelect select)) throw new Unverified("SOURCE_STRUCTURE_UNSUPPORTED");
        SqlNode from=select.getFrom();String alias=null;
        if(from instanceof SqlCall call && call.getKind()==SqlKind.AS) {
            if(call.operandCount()!=2 || !(call.operand(1) instanceof SqlIdentifier id) || !id.isSimple()) throw new Unverified("SOURCE_STRUCTURE_UNSUPPORTED");
            alias=id.getSimple();from=call.operand(0);
        }
        if(!(from instanceof SqlIdentifier table) || table.isStar() || table.names.size()>2) throw new Unverified("SOURCE_STRUCTURE_UNSUPPORTED");
        String name=table.names.get(table.names.size()-1),schema=table.names.size()==2?table.names.get(0):null;
        if(alias==null) alias=name;
        List<Projection> projections=new ArrayList<>();boolean star=false;
        for(SqlNode item:select.getSelectList()) {
            String label=null;SqlNode expression=item;
            if(item instanceof SqlCall call && call.getKind()==SqlKind.AS) {
                expression=call.operand(0);if(call.operand(1) instanceof SqlIdentifier id)label=id.getSimple();
            }
            if(expression instanceof SqlIdentifier id && id.names.size()<=2 &&
                (id.names.size()==1 || same(id.names.get(0),alias,dialect))) {
                if(id.isStar()) {if(label==null)star=true;}
                else {String column=id.names.get(id.names.size()-1);projections.add(new Projection(label==null?column:label,column));}
            }
        }
        // Top-level LIMIT 0, never a derived-table wrapper: MySQL rewrites origin fields inside wrappers.
        select.setOffset(null);select.setFetch(null);
        SqlNode zero=new SqlOrderBy(SqlParserPos.ZERO,select,root instanceof SqlOrderBy o?o.orderList:SqlNodeList.EMPTY,null,SqlLiteral.createExactNumeric("0",SqlParserPos.ZERO));
        return new Shape(name,schema,List.copyOf(projections),star,zero.toSqlString(resolver.getDialect(dialect)).getSql());
    }
}
