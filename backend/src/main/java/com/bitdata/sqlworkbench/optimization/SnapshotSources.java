package com.bitdata.sqlworkbench.optimization;
import java.sql.*;
import java.math.BigDecimal;
import java.util.*;
import org.apache.calcite.sql.*;

/** Transaction-local physical source and type evidence, separate from the existing name-only resolver. */
final class SnapshotSources {
  final ValidationControl control;
  final List<OptimizationSql.SourceEvidence> proofs=new ArrayList<>();
  SnapshotSources(ValidationControl control) {this.control=control;}
  record Info(int jdbc,String nativeType,int precision,int scale,boolean generated,boolean signed) {}
  static String qi(String dialect,String name) {String q=dialect.equals("mysql")?"`":"\"";return q+name.replace(q,q+q)+q;}
  static String table(String dialect,String schema,String name) {return qi(dialect,schema)+"."+qi(dialect,name);}
  void execute(Connection c,String sql) throws Exception {try(Statement s=c.createStatement()){try {control.attach(s);s.execute(sql);}finally{control.detach(s);}}}
  String scalar(Connection c,String sql) throws Exception {try(Statement s=c.createStatement()){try{control.attach(s);try(ResultSet r=s.executeQuery(sql)){if(!r.next())throw OptimizationSql.reject("ENVIRONMENT_UNAVAILABLE");return r.getString(1);}}finally{control.detach(s);}}}
  void proveTable(Connection c,String dialect,String schema,String name) throws Exception {
    if(dialect.equals("mysql")&&!schema.equals(c.getCatalog()))throw OptimizationSql.reject("SOURCE_UNSUPPORTED");
    String query=dialect.equals("mysql")?
      "SELECT TABLE_TYPE, ENGINE FROM information_schema.tables WHERE table_schema=? AND table_name=?":
      "SELECT c.relkind::text,c.relpersistence::text,c.relrowsecurity,EXISTS(SELECT 1 FROM pg_catalog.pg_inherits h WHERE h.inhparent=c.oid OR h.inhrelid=c.oid) FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname=? AND c.relname=?";
    try(PreparedStatement s=c.prepareStatement(query)){try{control.attach(s);s.setString(1,schema);s.setString(2,name);try(ResultSet r=s.executeQuery()){
      if(!r.next())throw OptimizationSql.reject("SOURCE_UNVERIFIED");
      if(dialect.equals("mysql")){if(!"BASE TABLE".equals(r.getString(1))||!"InnoDB".equals(r.getString(2)))throw OptimizationSql.reject("SOURCE_UNSUPPORTED");}
      else if(!"r".equals(r.getString(1))||!"p".equals(r.getString(2))||r.getBoolean(3)||r.getBoolean(4))throw OptimizationSql.reject("SOURCE_UNSUPPORTED");
    }}finally{control.detach(s);}}
  }
  void prepare(Connection c,String dialect,String... queries) throws Exception {
    var refs=new TreeMap<String,OptimizationSql.Reference>();
    for(String query:queries)for(var ref:OptimizationSql.policy(query,dialect))refs.put(ref.schema()+"\u0000"+ref.table(),ref);
    // First transaction work, before any catalog SELECT can establish an earlier PG MVCC snapshot.
    for(var ref:refs.values()) {
      String table=table(dialect,ref.schema(),ref.table());
      execute(c,dialect.equals("mysql")?"SELECT * FROM "+table+" LIMIT 0":"LOCK TABLE ONLY "+table+" IN ACCESS SHARE MODE");
    }
  }
  Map<String,Info> columns(Connection c,String dialect,OptimizationSql.Reference ref) throws Exception {

    proveTable(c,dialect,ref.schema(),ref.table());
    var result=new HashMap<String,Info>();
    String sql="SELECT * FROM "+table(dialect,ref.schema(),ref.table())+" LIMIT 0";
    try(Statement s=c.createStatement()) {
      control.attach(s);try(ResultSet r=s.executeQuery(sql)) {
      var m=r.getMetaData();
      for(int i=1;i<=m.getColumnCount();i++)result.put(m.getColumnLabel(i),new Info(m.getColumnType(i),m.getColumnTypeName(i),m.getPrecision(i),m.getScale(i),false,m.isSigned(i)));
    }}
    control.statement=null;
    String generatedSql=dialect.equals("mysql")?
      "SELECT column_name FROM information_schema.columns WHERE table_schema=? AND table_name=? AND extra LIKE '%GENERATED%'":
      "SELECT a.attname FROM pg_catalog.pg_attribute a JOIN pg_catalog.pg_class c ON c.oid=a.attrelid JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname=? AND c.relname=? AND a.attgenerated<>''";
    try(PreparedStatement s=c.prepareStatement(generatedSql)){control.attach(s);s.setString(1,ref.schema());s.setString(2,ref.table());try(ResultSet r=s.executeQuery()){
      while(r.next()){String name=r.getString(1);var old=result.get(name);if(old!=null)result.put(name,new Info(old.jdbc(),old.nativeType(),old.precision(),old.scale(),true,old.signed()));}
    }}
    control.statement=null;
    if(dialect.equals("postgresql")) {
      try(PreparedStatement st=c.prepareStatement("SELECT a.attname,t.typtype,n.nspname FROM pg_catalog.pg_attribute a JOIN pg_catalog.pg_class c ON c.oid=a.attrelid JOIN pg_catalog.pg_namespace ns ON ns.oid=c.relnamespace JOIN pg_catalog.pg_type t ON t.oid=a.atttypid JOIN pg_catalog.pg_namespace n ON n.oid=t.typnamespace WHERE ns.nspname=? AND c.relname=? AND a.attnum>0 AND NOT a.attisdropped")) {
        try{control.attach(st);st.setString(1,ref.schema());st.setString(2,ref.table());try(ResultSet r=st.executeQuery()){while(r.next())if(!"b".equals(r.getString(2))||!"pg_catalog".equals(r.getString(3))) {var old=result.get(r.getString(1));if(old!=null)result.put(r.getString(1),new Info(old.jdbc(),"unsupported",old.precision(),old.scale(),old.generated(),old.signed()));}}}finally{control.detach(st);}
      }
    }
    return Map.copyOf(result);
  }
  static Info column(SqlNode n,Map<String,Map<String,Info>> scope) {
    if(!(n instanceof SqlIdentifier id)||id.names.size()!=2||!scope.containsKey(id.names.get(0)))throw OptimizationSql.reject("COLUMN_UNRESOLVED");
    Info info=scope.get(id.names.get(0)).get(id.names.get(1));if(info==null)throw OptimizationSql.reject("COLUMN_UNRESOLVED");
    if(info.generated())throw OptimizationSql.reject("GENERATED_COLUMN_UNSUPPORTED");
    String name=info.nativeType().toLowerCase(Locale.ROOT);
    if(!Set.of("int2","int4","int8","numeric","varchar","text","tinyint","smallint","mediumint","int","bigint","decimal").contains(name))throw OptimizationSql.reject("TYPE_UNSUPPORTED");
    String family=ExactResultBag.family(info.jdbc());
    if(family.equals("EXACT_NUMBER")&&(!info.signed()||info.precision()<1||info.precision()>65||info.scale()<0||info.scale()>30))throw OptimizationSql.reject("NUMERIC_DOMAIN_UNSUPPORTED");
    return info;
  }
  static void numeric(SqlNode n,Map<String,Map<String,Info>> scope) {
    if(n instanceof SqlNumericLiteral lit) {
      if(!lit.isExact())throw OptimizationSql.reject("NUMERIC_DOMAIN_UNSUPPORTED");
      BigDecimal number=lit.bigDecimalValue();if(number.precision()>65||Math.abs(number.scale())>30)throw OptimizationSql.reject("NUMERIC_DOMAIN_UNSUPPORTED");return;
    }
    if(n instanceof SqlLiteral lit&&lit.getTypeName()==org.apache.calcite.sql.type.SqlTypeName.NULL)return;
    if(n instanceof SqlLiteral)throw OptimizationSql.reject("COMPARISON_DOMAIN_UNSUPPORTED");
    if(!ExactResultBag.family(column(n,scope).jdbc()).equals("EXACT_NUMBER"))throw OptimizationSql.reject("COMPARISON_DOMAIN_UNSUPPORTED");
  }
  static void condition(SqlNode n,Map<String,Map<String,Info>> scope) {
    if(n==null)return;SqlCall call=(SqlCall)n;
    if(call.getKind()==SqlKind.AND){for(var o:call.getOperandList())condition(o,scope);return;}
    if(call.getKind()==SqlKind.IS_NULL||call.getKind()==SqlKind.IS_NOT_NULL){column(call.operand(0),scope);return;}
    for(var o:call.getOperandList())numeric(o,scope);
  }
  void check(Connection c,String dialect,String sql) throws Exception {
    var refs=OptimizationSql.policy(sql,dialect);var evidence=new HashMap<OptimizationSql.Reference,Map<String,Info>>();
    for(var ref:refs)evidence.put(ref,columns(c,dialect,ref));
    var outer=OptimizationSql.select(OptimizationSql.parse(sql,dialect));var root=OptimizationSql.reference(outer.getFrom());
    Map<String,Map<String,Info>> scope=Map.of(root.alias(),evidence.get(root));
    for(var p:outer.getSelectList()) {
      if(p instanceof SqlCall as&&as.getKind()==SqlKind.AS)p=as.operand(0);column(p,scope);
    }
    // Retain only referenced columns in the report, bounded by the admitted AST.
    var used=new HashSet<String>();var pending=new ArrayDeque<SqlNode>();pending.push(outer);
    while(!pending.isEmpty()){var n=pending.pop();if(n instanceof SqlIdentifier i&&i.names.size()==2)used.add(i.names.get(0)+"\u0000"+i.names.get(1));if(n instanceof SqlCall call)for(var child:call.getOperandList())if(child!=null)pending.push(child);if(n instanceof SqlNodeList list)for(var child:list)if(child!=null)pending.push(child);}
    for(var ref:refs) {
      List<OptimizationSql.Column> cols=new ArrayList<>();for(var entry:evidence.get(ref).entrySet())if(used.contains(ref.alias()+"\u0000"+entry.getKey())){var i=entry.getValue();cols.add(new OptimizationSql.Column(entry.getKey(),i.jdbc(),i.nativeType(),i.precision(),i.scale(),i.signed()));}
      cols.sort(Comparator.comparing(OptimizationSql.Column::label));
      proofs.add(new OptimizationSql.SourceEvidence(ref.schema(),ref.table(),dialect.equals("mysql")?"BASE TABLE":"r",dialect.equals("mysql")?"InnoDB":null,Map.of("localPermanent","true","snapshotCapable","true","noRlsOrInheritance","true"),List.copyOf(cols)));
    }
    for(var term:OptimizationSql.conjuncts(outer.getWhere())) {
      SqlNode ex=term;
      try {OptimizationSql.condition(term,Set.of(root.alias()));condition(term,scope);continue;}
      catch(IllegalArgumentException rejected) {if(!rejected.getMessage().equals("PREDICATE_UNSUPPORTED")&&!rejected.getMessage().equals("EXPRESSION_UNSUPPORTED"))throw rejected;}
      if(!(term instanceof SqlCall exists&&exists.getKind()==SqlKind.EXISTS))ex=OptimizationSql.rewrite(term,root);
      var sub=(SqlSelect)((SqlCall)ex).operand(0);var inner=OptimizationSql.reference(sub.getFrom());
      var subScope=new HashMap<String,Map<String,Info>>(scope);subScope.put(inner.alias(),evidence.get(inner));condition(sub.getWhere(),subScope);
    }
  }
}
