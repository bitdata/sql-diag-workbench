package com.bitdata.sqlworkbench.optimization;
import com.bitdata.sqlworkbench.connection.DatabaseStrategyFactory;
import com.bitdata.sqlworkbench.sql.SqlDialectResolver;
import com.bitdata.sqlworkbench.sql.NodeToSql;
import org.apache.calcite.avatica.util.Casing;
import org.apache.calcite.sql.*;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.sql.parser.*;
import java.util.*;
import com.fasterxml.jackson.annotation.JsonInclude;

/** Independent, bounded optimization AST policy. Never changes fragment identity. */
public final class OptimizationSql {
  static final SqlParserPos P=SqlParserPos.ZERO;
  static final SqlDialectResolver RESOLVER=new SqlDialectResolver(DatabaseStrategyFactory.withBuiltinStrategies());
  public record Source(String sessionId,String sourceRevision,String sql,String connectionId,String connectionRevision,String dialect) {}
  public record CandidateRequest(String sessionId,String sourceRevision,String sql,String connectionId,String dialect) {}
  public record Reference(String schema,String table,String alias) {}
  public record Issue(String code,String path,String message) {}
  public record Candidate(String id,String ruleId,int ruleVersion,String path,String sql,List<String> conditions) {}
  public record Generation(int version,Source source,List<Candidate> candidates,List<Issue> skipped) {}
  @JsonInclude(JsonInclude.Include.ALWAYS)
  public record ValidationRequest(Source source,String candidateSql,String candidateId,Integer timeoutSeconds) {}
  public record Submission(String validationId) {}
  public record ReportSummary(int version,String validationId,String completedAt,String verdict,String originalPreview,String candidatePreview) {}
  @JsonInclude(JsonInclude.Include.ALWAYS)
  public record ReportPage(int version,List<ReportSummary> items,String nextCursor) {}
  @JsonInclude(JsonInclude.Include.ALWAYS)
  public record Cell(String kind,String value) {}
  public record Column(String label,int jdbcType,String typeName,int precision,int scale,boolean signed) {}
  public record Difference(List<Cell> cells,String originalCount,String candidateCount) {}
  public record Side(String rowCount,boolean complete,List<Column> columns) {}
  @JsonInclude(JsonInclude.Include.ALWAYS)
  public record SourceEvidence(String schema,String table,String kind,String engine,Map<String,String> checks,List<Column> columns) {}
  public record Snapshot(String state,String product,String serverVersion,String database,String databaseUser,String isolation,boolean readOnly,String sessionIdentity,Map<String,String> settings,List<SourceEvidence> sources) {}
  @JsonInclude(JsonInclude.Include.ALWAYS)
  public record Persistence(String status,String errorReason) {}
  @JsonInclude(JsonInclude.Include.ALWAYS)
  public record Report(int version,String validationId,String status,String verdict,String errorReason,boolean degraded,
    Source source,String candidateSql,String candidateId,Candidate candidateEvidence,String comparisonMode,Snapshot snapshot,Side original,Side candidate,
    List<Difference> differences,boolean differencesTruncated,String startedAt,String completedAt,Persistence persistence) {}

  static IllegalArgumentException reject(String code) { return new IllegalArgumentException(code); }
  static SqlNode parse(String sql,String dialect) throws Exception {
    if(!Set.of("postgresql","mysql").contains(dialect))throw reject("DIALECT_UNSUPPORTED");
    if(sql==null||sql.isBlank()||sql.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>32768)throw reject("SQL_BUDGET_EXCEEDED");
    // Server-executed comments cannot be certified by a parser that treats them as trivia.
    if(sql.contains("/*!")||sql.toUpperCase(java.util.Locale.ROOT).contains("/*M!")||sql.contains("/*+"))throw reject("QUERY_SHAPE_UNSUPPORTED");
    // Exact spelling: PostgreSQL unquoted identifiers resolve lowercase; quoted names retain case.
    try {
      SqlNode parsed=SqlParser.create(sql,RESOLVER.parserConfig(dialect).withUnquotedCasing("postgresql".equals(dialect)?Casing.TO_LOWER:Casing.UNCHANGED)).parseQuery();
      record Entry(SqlNode node,int depth) {}
      ArrayDeque<Entry> pending=new ArrayDeque<>();pending.push(new Entry(parsed,0));int count=0;
      while(!pending.isEmpty()) {
        var entry=pending.pop();if(++count>2048||entry.depth()>64)throw reject("AST_BUDGET_EXCEEDED");
        if(entry.node() instanceof SqlCall call)for(var child:call.getOperandList())if(child!=null)pending.push(new Entry(child,entry.depth()+1));
        if(entry.node() instanceof SqlNodeList list)for(var child:list)if(child!=null)pending.push(new Entry(child,entry.depth()+1));
      }
      new com.bitdata.sqlworkbench.sql.SelectOnlyGuard(RESOLVER).requireSingleSelect(sql,dialect);
      return parsed;
    }
    catch(SqlParseException failure){throw reject("SQL_PARSE_FAILED");}
    catch(StackOverflowError limit){throw reject("AST_BUDGET_EXCEEDED");}
  }
  static SqlSelect select(SqlNode node) {
    if(!(node instanceof SqlSelect s))throw reject("QUERY_SHAPE_UNSUPPORTED");
    if(s.isDistinct()||s.getGroup()!=null||s.getHaving()!=null||s.getQualify()!=null||
      (s.getWindowList()!=null&&!s.getWindowList().isEmpty())||(s.getOrderList()!=null&&!s.getOrderList().isEmpty())||
      s.getOffset()!=null||s.getFetch()!=null||s.getHints()!=null&&!s.getHints().isEmpty())throw reject("QUERY_SHAPE_UNSUPPORTED");
    return s;
  }
  static Reference reference(SqlNode node) {
    if(!(node instanceof SqlCall as)||as.getKind()!=SqlKind.AS||as.operandCount()!=2||
       !(as.operand(0) instanceof SqlIdentifier table)||table.isStar()||table.names.size()!=2||
       !(as.operand(1) instanceof SqlIdentifier alias)||alias.names.size()!=1)throw reject("SOURCE_SHAPE_UNSUPPORTED");
    return new Reference(table.names.get(0),table.names.get(1),alias.getSimple());
  }
  static boolean column(SqlNode n,Set<String> aliases) {
    return n instanceof SqlIdentifier i&&!i.isStar()&&i.names.size()==2&&aliases.contains(i.names.get(0));
  }
  static void scalar(SqlNode n,Set<String> aliases) {
    if(n instanceof SqlLiteral)return;
    if(!column(n,aliases))throw reject("EXPRESSION_UNSUPPORTED");
  }
  static void condition(SqlNode n,Set<String> aliases) {
    if(n==null)return;
    if(!(n instanceof SqlCall c))throw reject("PREDICATE_UNSUPPORTED");
    if(c.getKind()==SqlKind.AND) { for(var o:c.getOperandList())condition(o,aliases); return; }
    if(!Set.of(SqlKind.EQUALS,SqlKind.NOT_EQUALS,SqlKind.GREATER_THAN,SqlKind.LESS_THAN,SqlKind.GREATER_THAN_OR_EQUAL,SqlKind.LESS_THAN_OR_EQUAL,SqlKind.IS_NULL,SqlKind.IS_NOT_NULL).contains(c.getKind()))throw reject("PREDICATE_UNSUPPORTED");
    for(var o:c.getOperandList())scalar(o,aliases);
  }
  static List<SqlNode> conjuncts(SqlNode n) {
    if(n==null)return List.of();
    if(n instanceof SqlCall c&&c.getKind()==SqlKind.AND) {
      List<SqlNode> list=new ArrayList<>();for(var x:c.getOperandList())list.addAll(conjuncts(x));return list;
    }
    return List.of(n);
  }
  static void projections(SqlSelect outer,Reference source) {
    for(SqlNode value:outer.getSelectList()) {
      if(value instanceof SqlCall as&&as.getKind()==SqlKind.AS&&as.operandCount()==2&&as.operand(1) instanceof SqlIdentifier)value=as.operand(0);
      if(!column(value,Set.of(source.alias())))throw reject("PROJECTION_UNSUPPORTED");
    }
  }
  static SqlSelect inner(SqlNode n,Reference outer) {
    SqlSelect sub=select(n);Reference in=reference(sub.getFrom());
    if(in.alias().equalsIgnoreCase(outer.alias()))throw reject("ALIAS_SHADOWING_UNSUPPORTED");
    condition(sub.getWhere(),Set.of(in.alias(),outer.alias()));return sub;
  }
  static SqlNode rewrite(SqlNode term,Reference outer) {
    SqlSelect sub;SqlNode extra=null;
    if(term instanceof SqlCall in&&in.getKind()==SqlKind.IN&&in.operandCount()==2) {
      if(!column(in.operand(0),Set.of(outer.alias())))throw reject("IN_OPERAND_UNSUPPORTED");
      sub=inner(in.operand(1),outer);Reference input=reference(sub.getFrom());
      if(sub.getSelectList().size()!=1||!column(sub.getSelectList().get(0),Set.of(input.alias())))throw reject("IN_PROJECTION_UNSUPPORTED");
      extra=SqlStdOperatorTable.EQUALS.createCall(P,sub.getSelectList().get(0),in.operand(0));
    } else if(term instanceof SqlCall gt&&gt.getKind()==SqlKind.GREATER_THAN&&gt.operandCount()==2&&
        gt.operand(1) instanceof SqlNumericLiteral zero&&zero.bigDecimalValue().compareTo(java.math.BigDecimal.ZERO)==0) {
      SqlNode query=gt.operand(0);
      if(query instanceof SqlCall scalar&&scalar.getKind()==SqlKind.SCALAR_QUERY&&scalar.operandCount()==1)query=scalar.operand(0);
      sub=inner(query,outer);
      if(sub.getSelectList().size()!=1||!(sub.getSelectList().get(0) instanceof SqlCall count)||!count.getOperator().getName().equalsIgnoreCase("count")||
        count.getOperator().getNameAsId().names.size()!=1||count.getOperator().getNameAsId().isComponentQuoted(0)||
        count.getFunctionQuantifier()!=null||count.operandCount()!=1||!(count.operand(0) instanceof SqlIdentifier star)||!star.isStar()||star.names.size()!=1)throw reject("COUNT_FORM_UNSUPPORTED");
    } else throw reject("RULE_NOT_APPLICABLE");
    sub=(SqlSelect)sub.clone(P);
    sub.setSelectList(new SqlNodeList(List.of(SqlLiteral.createExactNumeric("1",P)),P));
    if(extra!=null)sub.setWhere(sub.getWhere()==null?extra:SqlStdOperatorTable.AND.createCall(P,sub.getWhere(),extra));
    return SqlStdOperatorTable.EXISTS.createCall(P,sub);
  }
  public static Generation generate(Source source) throws Exception {
    policy(source.sql(),source.dialect());
    SqlSelect original=select(parse(source.sql(),source.dialect()));Reference ref=reference(original.getFrom());projections(original,ref);
    var terms=conjuncts(original.getWhere());List<Candidate> candidates=new ArrayList<>();List<Issue> skipped=new ArrayList<>();
    if(terms.size()>16)throw reject("CANDIDATE_BUDGET_EXCEEDED");
    for(int index=0;index<terms.size();index++) {
      String path="query.where.and["+index+"]";
      try {
        // Fresh parse for each candidate: no cross-candidate AST mutation.
        SqlSelect copy=select(parse(source.sql(),source.dialect()));var items=new ArrayList<>(conjuncts(copy.getWhere()));
        SqlNode replacement=rewrite(items.get(index),ref);
        for(int i=0;i<items.size();i++)if(i!=index) {
          try { condition(items.get(i),Set.of(ref.alias())); }
          catch(IllegalArgumentException notPlain) { supportedExistence(items.get(i),ref); }
        }
        String rule=items.get(index).getKind()==SqlKind.IN?"positive-in-exists":"count-star-exists";
        items.set(index,replacement);SqlNode where=items.get(0);for(int i=1;i<items.size();i++)where=SqlStdOperatorTable.AND.createCall(P,where,items.get(i));
        copy.setWhere(where);String sql=NodeToSql.toQuotedSql(copy,RESOLVER.getProfile(source.dialect()));
        // Source/id are exact texts, never fragment canonical identity.
        if(sql.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>32768)throw reject("SQL_BUDGET_EXCEEDED");
        String id=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest((source.sql()+"\u0000"+rule+"\u0000"+path+"\u0000"+sql).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        candidates.add(new Candidate(id,rule,1,path,sql,List.of("SUPPORTED_LOCAL_TABLES","BUILTIN_EXACT_TYPES","DETERMINISTIC_SUPPORTED_QUERY","SAME_SNAPSHOT_VALIDATION_REQUIRED","NO_SPEEDUP_CLAIM")));
      } catch(IllegalArgumentException e) { skipped.add(new Issue(e.getMessage(),path,"No supported rewrite for this term")); }
    }
    if(terms.isEmpty())skipped.add(new Issue("NO_SUPPORTED_PREDICATE","query.where","No supported existence predicate"));
    return new Generation(1,source,List.copyOf(candidates),List.copyOf(skipped));
  }
  static SqlSelect supportedExistence(SqlNode term,Reference root) {
    if(term instanceof SqlCall exists&&exists.getKind()==SqlKind.EXISTS&&exists.operandCount()==1) {
      SqlSelect sub=inner(exists.operand(0),root);
      if(sub.getSelectList().size()!=1||!(sub.getSelectList().get(0) instanceof SqlNumericLiteral one)||one.bigDecimalValue().compareTo(java.math.BigDecimal.ONE)!=0)throw reject("EXISTS_FORM_UNSUPPORTED");
      return sub;
    }
    return (SqlSelect)((SqlCall)rewrite(term,root)).operand(0);
  }
  // Shared AST admission; metadata/parameter checks remain server-side in one validation connection.
  static List<Reference> policy(String sql,String dialect) throws Exception {
    SqlSelect outer=select(parse(sql,dialect));Reference root=reference(outer.getFrom());projections(outer,root);
    LinkedHashSet<Reference> refs=new LinkedHashSet<>();refs.add(root);
    var terms=conjuncts(outer.getWhere());if(terms.size()>16)throw reject("CANDIDATE_BUDGET_EXCEEDED");
    for(var term:terms) {
      if(term instanceof SqlCall exists&&exists.getKind()==SqlKind.EXISTS) {
        SqlSelect sub=inner(exists.operand(0),root);
        if(sub.getSelectList().size()!=1||!(sub.getSelectList().get(0) instanceof SqlNumericLiteral one)||one.bigDecimalValue().compareTo(java.math.BigDecimal.ONE)!=0)throw reject("EXISTS_FORM_UNSUPPORTED");
        refs.add(reference(sub.getFrom()));
      } else {
        try {condition(term,Set.of(root.alias()));}
        catch(IllegalArgumentException other) {
          SqlNode rewritten=rewrite(term,root);refs.add(reference(((SqlSelect)((SqlCall)rewritten).operand(0)).getFrom()));
        }
      }
    }
    if(refs.size()>8)throw reject("SOURCE_BUDGET_EXCEEDED");return List.copyOf(refs);
  }
}
