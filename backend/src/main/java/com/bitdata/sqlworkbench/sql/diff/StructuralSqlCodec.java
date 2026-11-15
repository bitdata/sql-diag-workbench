package com.bitdata.sqlworkbench.sql.diff;

import com.bitdata.sqlworkbench.sql.tsql.*;
import com.bitdata.sqlworkbench.fragment.FragmentIdentity;
import com.bitdata.sqlworkbench.sql.*;
import org.apache.calcite.sql.*;
import org.apache.calcite.sql.fun.SqlBetweenOperator;
import org.apache.calcite.sql.parser.SqlParser;
import java.util.*;

/** Lossless shared identity and diff representation for structural-p0p1-v2 contexts.
 * Canonical content is opaque and must never be executed or shown as SQL. */
public final class StructuralSqlCodec {
  private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();
  private static final SqlDialectResolver RESOLVER = new SqlDialectResolver(com.bitdata.sqlworkbench.connection.DatabaseStrategyFactory.withBuiltinStrategies());
  public static final String POLICY = "structural-p0p1-v2";
  public record Tree(String tag, String value, List<Tree> children) {
    public Tree { children = List.copyOf(children); }
    Object wire() { return List.of(tag, value, children.stream().map(Tree::wire).toList()); }
    String key() { return json(wire()); }
  }
  public record Form(String policy, String dialect, String canonical, String id,
                     List<NodeToken> tokens, boolean degraded, String errorReason) {}
  public record Delta(Set<ChangeOp> ops, boolean degraded, String errorReason, String method) {}
  public record DisplayOp(String kind,String nodeType,String path,String oldSql,String newSql,String displayScope,String comparisonKey) {}
  static Tree leaf(String tag, Object value) { return new Tree(tag, json(value), List.of()); }
  static Tree tree(String tag, Tree... children) { return new Tree(tag, "", Arrays.asList(children)); }
  static String json(Object value) {
    try { return JSON.writeValueAsString(value); }
    catch(Exception e) { throw new IllegalStateException("CONTRACT_ENCODING_FAILED", e); }
  }
  static final Tree NIL = leaf("ABSENT", null), EMPTY = new Tree("LIST", "", List.of());
  static Tree list(SqlNodeList nodes, String dialect) { return nodes == null ? EMPTY : shape(nodes, dialect); }
  public static SqlNode parse(String sql, String dialect) throws org.apache.calcite.sql.parser.SqlParseException {
    return SqlParser.create(sql, RESOLVER.parserConfig(dialect)).parseQuery();
  }
  public static Form canonicalize(String sql, String dialect) {
    try {
      if(!Set.of("sqlserver", "postgresql", "mysql", "oracle").contains(dialect))
        throw new IllegalArgumentException("DIALECT_UNSUPPORTED");
      return fromAst(parse(sql, dialect), dialect);
    } catch(Exception failure) {
      return new Form(POLICY, dialect, null, null, List.of(), true,
        failure instanceof IllegalArgumentException ? failure.getMessage() : "PARSE_OR_ENCODING_UNSUPPORTED:" + failure.getClass().getSimpleName());
    }
  }

  /** Same representation for already parsed nodes; no second unparse/parse normalization. */
  public static Form fromAst(SqlNode node, String dialect) {
    try {
      if (node == null) throw new IllegalArgumentException("AST_ROOT_REQUIRED");
      if (dialect == null || !Set.of("sqlserver", "postgresql", "mysql", "oracle").contains(dialect))
        throw new IllegalArgumentException("DIALECT_UNSUPPORTED");
      Tree normalized = normalize(shape(node, dialect));
      String canonical = normalized.key();
      return new Form(POLICY,dialect,canonical,FragmentIdentity.computeId(canonical,dialect,POLICY),
        tokens(normalized),false,null);
    } catch (RuntimeException failure) {
      return new Form(POLICY,dialect,null,null,List.of(),true,
        failure.getMessage() == null ? "AST_ENCODING_UNSUPPORTED" : failure.getMessage());
    }
  }

  private static List<NodeToken> tokens(Tree tree) {
    var out = new ArrayList<NodeToken>();
    out.add(new NodeToken("FORMAT",POLICY,"format"));
    emit(tree,"root",out);
    return List.copyOf(out);
  }

  /** Old flat tokens cannot establish a new structural identity. */
  public static List<NodeToken> canonicalizeTokens(List<NodeToken> sequence) {
    if (sequence == null || sequence.size() < 2 || !sequence.get(0).nodeType().equals("FORMAT")
        || !sequence.get(0).canonicalText().equals(POLICY))
      throw new IllegalArgumentException("STRUCTURAL_TOKEN_FORMAT_REQUIRED");
    var cursor = new TokenCursor(sequence);
    Tree tree = cursor.read(0);
    if (cursor.index != sequence.size()) throw new IllegalArgumentException("STRUCTURAL_TOKEN_TRAILING_CONTENT");
    validateTags(tree);
    return tokens(normalize(tree));
  }

  private static void validateTags(Tree tree) {
    boolean known = Set.of("ABSENT","LIST","IDENT_COMPONENTS","NATIONAL_LITERAL","CHAR_LITERAL",
      "NUMERIC_LITERAL","LITERAL","INTERVAL_QUALIFIER","NATIVE_TYPE","BASIC_TYPE","TYPE_SPEC",
      "NULLABLE","TIMEZONE","QUERY","SELECT","WITH","CTE","BUILTIN_FUNCTION","FUNCTION",
      "OPERATOR","BETWEEN_OPERATOR").contains(tree.tag);
    if (!known && tree.tag.startsWith("CALL_")) {
      try { org.apache.calcite.sql.SqlKind.valueOf(tree.tag.substring(5)); known = true; }
      catch (IllegalArgumentException unsupported) { throw new IllegalArgumentException("STRUCTURAL_TREE_TAG_UNSUPPORTED",unsupported); }
    }
    if (!known) throw new IllegalArgumentException("STRUCTURAL_TREE_TAG_UNSUPPORTED");
    for (var child : tree.children) validateTags(child);
  }

  private static final class TokenCursor {
    final List<NodeToken> sequence;
    int index = 1;
    TokenCursor(List<NodeToken> sequence) { this.sequence = sequence; }
    Tree read(int depth) {
      if (depth > 256 || index >= sequence.size()) throw new IllegalArgumentException("STRUCTURAL_TOKEN_UNBALANCED");
      var token = sequence.get(index++);
      String type = token.nodeType();
      if (type.equals("EXPRESSION")) {
        try {
          Tree expression = decode(JSON.readTree(token.canonicalText()),depth+1);
          if (!expression.tag.startsWith("CALL_") || isBoolean(expression) || containsQuery(expression))
            throw new IllegalArgumentException("STRUCTURAL_EXPRESSION_INVALID");
          return expression;
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
          throw new IllegalArgumentException("STRUCTURAL_EXPRESSION_INVALID",failure);
        }
      }
      if (type.startsWith("VALUE_")) return new Tree(type.substring(6),token.canonicalText(),List.of());
      if (!type.startsWith("OPEN_")) throw new IllegalArgumentException("STRUCTURAL_TOKEN_INVALID");
      String tag = type.substring(5);
      var children = new ArrayList<Tree>();
      while (index < sequence.size() && !sequence.get(index).nodeType().startsWith("CLOSE_")) children.add(read(depth+1));
      if (index >= sequence.size() || !sequence.get(index++).nodeType().equals("CLOSE_"+tag))
        throw new IllegalArgumentException("STRUCTURAL_TOKEN_UNBALANCED");
      return new Tree(tag,token.canonicalText(),children);
    }
  }

  private static Tree decode(com.fasterxml.jackson.databind.JsonNode value, int depth) {
    if (depth > 256 || !value.isArray() || value.size() != 3 || !value.get(0).isTextual()
        || !value.get(1).isTextual() || !value.get(2).isArray())
      throw new IllegalArgumentException("STRUCTURAL_TREE_INVALID");
    var children = new ArrayList<Tree>();
    for (var child : value.get(2)) children.add(decode(child,depth+1));
    return new Tree(value.get(0).textValue(),value.get(1).textValue(),children);
  }
  // Every semantic field is encoded with a type/role and JSON boundaries. Parser positions
  // and source quote decoration are absent; decoded identifier components retain their bytes.
  static Tree shape(SqlNode n, String d) {
    if(n == null) return NIL;
    if(n instanceof SqlIdentifier id) return leaf("IDENT_COMPONENTS", id.names);
    if(n instanceof TsqlParserSupport.NationalLiteral literal) return leaf("NATIONAL_LITERAL", literal.text());
    if(n instanceof com.bitdata.sqlworkbench.sql.oracle.OracleParserAdapter.NationalLiteral literal) return leaf("NATIONAL_LITERAL",literal.text());
    if(n instanceof SqlCharStringLiteral literal) {
      var value = literal.getNlsString();
      return leaf("CHAR_LITERAL", Arrays.asList(value.getValue(), value.getCharsetName(),
        value.getCollation() == null ? null : value.getCollation().getCollationName()));
    }
    if(n instanceof SqlNumericLiteral literal)
      return leaf("NUMERIC_LITERAL", List.of(literal.isExact(), literal.toValue()));
    if(n instanceof SqlLiteral literal) return leaf("LITERAL", List.of(literal.getTypeName().name(), literal.toString()));
    if(n instanceof SqlIntervalQualifier q)
      return leaf("INTERVAL_QUALIFIER", Arrays.asList(q.timeFrameName, q.timeUnitRange.name(),
        q.getStartPrecisionPreservingDefault(), q.getFractionalSecondPrecisionPreservingDefault()));
    if(n instanceof SqlDataTypeSpec spec) {
      Tree type;
      if(spec.getTypeNameSpec() instanceof com.bitdata.sqlworkbench.sql.oracle.OracleParserAdapter.NativeType t) {
        type = leaf("NATIVE_TYPE", List.of(t.nativeName(), t.arguments()));
      } else if(spec.getTypeNameSpec() instanceof TsqlParserSupport.NativeTypeSpec t)
        type = leaf("NATIVE_TYPE", List.of(t.nativeName(), t.arguments()));
      else if(spec.getTypeNameSpec().getClass() == SqlBasicTypeNameSpec.class) {
        var t = (SqlBasicTypeNameSpec)spec.getTypeNameSpec();
        type = leaf("BASIC_TYPE", Arrays.asList(t.getTypeName().names, t.getPrecision(), t.getScale(), t.getCharSetName()));
      } else throw new IllegalArgumentException("TYPE_UNSUPPORTED:" + spec.getTypeNameSpec().getClass().getSimpleName());
      return tree("TYPE_SPEC", type, leaf("NULLABLE", spec.getNullable()), leaf("TIMEZONE", spec.getTimeZone()));
    }
    if(n instanceof SqlNodeList nodes) return new Tree("LIST", "", nodes.getList().stream().map(x -> shape(x,d)).toList());
    if(n instanceof SqlOrderBy order) {
      Tree query = shape(order.query,d);
      if(!query.tag.equals("QUERY")) throw new IllegalArgumentException("QUERY_WRAPPER_UNSUPPORTED");
      var c = query.children;
      Tree ordering = order.orderList == null || order.orderList.isEmpty() ? c.get(1) : shape(order.orderList,d);
      if(order.orderList != null && !order.orderList.isEmpty() && !c.get(1).equals(EMPTY))
        throw new IllegalArgumentException("NESTED_ORDER_UNSUPPORTED");
      if(order.offset != null && !c.get(2).equals(NIL) || order.fetch != null && !c.get(3).equals(NIL))
        throw new IllegalArgumentException("NESTED_PAGINATION_UNSUPPORTED");
      return tree("QUERY", c.get(0), ordering, order.offset == null ? c.get(2) : shape(order.offset,d),
        order.fetch == null ? c.get(3) : shape(order.fetch,d));
    }
    if(n instanceof SqlSelect s) {
      Tree modifiers = new Tree("LIST", "", s.getOperandList().get(0) instanceof SqlNodeList keywords
        ? keywords.getList().stream().filter(k -> !k.toString().equals("ALL")).map(k -> shape(k,d)).toList() : List.of());
      Tree body = tree("SELECT", modifiers, shape(s.getSelectList(),d), shape(s.getFrom(),d), shape(s.getWhere(),d),
        shape(s.getGroup(),d), shape(s.getHaving(),d), list(s.getWindowList(),d), shape(s.getQualify(),d), list(s.getHints(),d));
      return tree("QUERY", body, list(s.getOrderList(),d), shape(s.getOffset(),d), shape(s.getFetch(),d));
    }
    if(n instanceof SqlWith with)
      return tree("QUERY", tree("WITH", shape(with.withList,d), shape(with.body,d)), EMPTY, NIL, NIL);
    if(n instanceof SqlWithItem item)
      return tree("CTE", shape(item.name,d), shape(item.columnList,d), shape(item.recursive,d), shape(item.query,d));
    if(n instanceof SqlCall call) {
      if(!(call instanceof SqlBasicCall || call instanceof SqlJoin || call instanceof SqlWindow || call instanceof org.apache.calcite.sql.fun.SqlCase))
        throw new IllegalArgumentException("CALL_CLASS_UNSUPPORTED:" + call.getClass().getSimpleName());
      String kind = call.getKind().name();
      Tree op;
      if(call.getOperator() instanceof SqlFunction fn && fn.getSqlIdentifier() != null) {
        var name=fn.getSqlIdentifier();
        boolean knownBuiltin=name.isSimple()&&!name.isComponentQuoted(0)&&Set.of("GREATEST","LEAST","ISNULL","SQL_VARIANT_PROPERTY","DATALENGTH","ABS","DATEADD")
          .contains(name.getSimple().toUpperCase(Locale.ROOT));
        op=knownBuiltin?leaf("BUILTIN_FUNCTION",name.getSimple().toUpperCase(Locale.ROOT)):tree("FUNCTION",shape(name,d));
      }
      else op = leaf("OPERATOR", call.getOperator().getName());
      if(call.getOperator() instanceof SqlBetweenOperator between)
        op = leaf("BETWEEN_OPERATOR", List.of(between.flag.name(), between.isNegated()));
      var children = new ArrayList<Tree>(); children.add(op); children.add(shape(call.getFunctionQuantifier(),d));
      for(SqlNode operand : call.getOperandList()) children.add(shape(operand,d));
      Tree body = new Tree("CALL_" + kind, "", children);
      return Set.of("UNION", "INTERSECT", "EXCEPT", "VALUES").contains(kind)
        ? tree("QUERY", body, EMPTY, NIL, NIL) : body;
    }
    throw new IllegalArgumentException("NODE_UNSUPPORTED:" + n.getClass().getSimpleName());
  }
  static boolean isBoolean(Tree t) { return t.tag.equals("CALL_AND") || t.tag.equals("CALL_OR"); }
  static Tree call(String kind, List<Tree> operands) {
    var children = new ArrayList<Tree>(); children.add(leaf("OPERATOR", kind)); children.add(NIL); children.addAll(operands);
    return new Tree("CALL_"+kind,"",children);
  }
  public static Tree normalize(Tree t) {
    var c = new ArrayList<Tree>(t.children.stream().map(StructuralSqlCodec::normalize).toList());
    if(isBoolean(t)) {
      var operands = new ArrayList<Tree>();
      for(Tree operand : c.subList(2,c.size())) {
        if(operand.tag.equals(t.tag)) operands.addAll(operand.children.subList(2,operand.children.size()));
        else operands.add(operand);
      }
      operands.sort(Comparator.comparing(Tree::key)); // Whole subtrees; multiplicity retained.
      return call(t.tag.substring(5), operands);
    }
    if(t.tag.equals("CALL_NOT") && c.size()==3) {
      Tree operand = c.get(2);
      if(operand.tag.equals("CALL_NOT")) return operand.children.get(2);
      if(isBoolean(operand)) return normalize(call(operand.tag.equals("CALL_AND")?"OR":"AND",
        operand.children.subList(2,operand.children.size()).stream().map(x -> call("NOT",List.of(x))).toList()));
      return call("NOT",List.of(operand));
    }
    if(Set.of("CALL_LESS_THAN","CALL_GREATER_THAN","CALL_LESS_THAN_OR_EQUAL","CALL_GREATER_THAN_OR_EQUAL").contains(t.tag)) {
      Tree a=c.get(2), b=c.get(3);
      // Preserve column-left convention, otherwise order by lossless subtree key.
      boolean swap = !a.tag.equals("IDENT_COMPONENTS") && b.tag.equals("IDENT_COMPONENTS")
        || a.tag.equals("IDENT_COMPONENTS")==b.tag.equals("IDENT_COMPONENTS") && b.key().compareTo(a.key())<0;
      String kind=t.tag.substring(5);
      if(swap) kind=switch(kind) { case "LESS_THAN"->"GREATER_THAN"; case "GREATER_THAN"->"LESS_THAN";
        case "LESS_THAN_OR_EQUAL"->"GREATER_THAN_OR_EQUAL"; default->"LESS_THAN_OR_EQUAL"; };
      return call(kind,swap?List.of(b,a):List.of(a,b));
    }
    if(t.tag.equals("CALL_NOT_EQUALS")) return call("NOT_EQUALS",c.subList(2,c.size()));
    return new Tree(t.tag,t.value,c);
  }
  // Balanced tokens carry structure in matching content. Paths are diagnostic locations,
  // not identity bytes: adding an earlier sibling does not invalidate the surviving content.
  static void emit(Tree t, String path, List<NodeToken> out) {
    // Predicate/function text is a typed lossless subtree payload, never unparse text.
    // This preserves ADR-0004's one predicate MOD while group boundaries stay explicit.
    if(t.tag.startsWith("CALL_")&&!t.tag.equals("CALL_JOIN")&&!isBoolean(t)&&!containsQuery(t)) {
      out.add(new NodeToken("EXPRESSION",t.key(),path));return;
    }
    if(t.children.isEmpty()) {out.add(new NodeToken("VALUE_"+t.tag,t.value,path));return;}
    out.add(new NodeToken("OPEN_"+t.tag,t.value,path+"/open"));
    var occurrences = new HashMap<String,Integer>();
    for(int i=0;i<t.children.size();i++) {
      Tree child=t.children.get(i);
      String location = Integer.toString(i);
      if (isBoolean(t) && i >= 2) {
        // Paths are diagnostics, but MOD pairing and delta-set containment need
        // a stable subject when a comparison changes its sort position.
        Tree subject = comparisonSubject(child);
        String key = FragmentIdentity.sha256Hex(subject.key());
        int occurrence = occurrences.merge(key,1,Integer::sum);
        location = "operand["+key+"]/"+occurrence;
      }
      emit(child,path+"/"+location,out);
    }
    out.add(new NodeToken("CLOSE_"+t.tag,"",path+"/close"));
  }
  private static Tree comparisonSubject(Tree tree) {
    return Set.of("CALL_EQUALS","CALL_NOT_EQUALS","CALL_LESS_THAN","CALL_LESS_THAN_OR_EQUAL",
      "CALL_GREATER_THAN","CALL_GREATER_THAN_OR_EQUAL").contains(tree.tag) && tree.children.size() == 4
      ? tree.children.get(2) : tree;
  }
  static boolean containsQuery(Tree t) {return t.tag.equals("QUERY")||t.children.stream().anyMatch(StructuralSqlCodec::containsQuery);}
  public static Delta diff(Form a, Form b) { return compare(a,b); }
  private static DialectProfile displayProfile(String dialect) {
    return RESOLVER.getProfile(dialect);
  }
  static Map<String,String> displaySnippets(String sql,String dialect) throws Exception {
    var snippets=new HashMap<String,String>();
    parse(sql,dialect).accept(new org.apache.calcite.sql.util.SqlShuttle(){
      void capture(SqlNode node){
        Tree tree=normalize(shape(node,dialect));var tokens=new ArrayList<NodeToken>();emit(tree,"root",tokens);
        String text=NodeToSql.toSql(node,displayProfile(dialect));
        if(tokens.size()==1)snippets.put(tokens.get(0).nodeType()+"|"+tokens.get(0).canonicalText(),text);
      }
      @Override public SqlNode visit(SqlCall call){capture(call);return super.visit(call);}
      @Override public SqlNode visit(SqlIdentifier id){capture(id);return id;}
      @Override public SqlNode visit(SqlLiteral literal){capture(literal);return literal;}
      @Override public SqlNode visit(SqlDataTypeSpec type){capture(type);return type;}
    });return snippets;
  }
  public static List<DisplayOp> display(Delta delta,String before,String after,String dialect) throws Exception {
    var left=displaySnippets(before,dialect);var right=displaySnippets(after,dialect);var result=new ArrayList<DisplayOp>();
    for(var op:delta.ops){
      String l=op.oldText()==null?null:left.get(op.nodeType()+"|"+op.oldText());
      String r=op.newText()==null?null:right.get(op.nodeType()+"|"+op.newText());
      boolean scoped=(op.oldText()==null||l!=null)&&(op.newText()==null||r!=null);
      result.add(new DisplayOp(op.kind().name(),op.nodeType(),op.path(),op.oldText()==null?null:l==null?before:l,
        op.newText()==null?null:r==null?after:r,scoped?"AST_NODE":"WHOLE_SQL_CONTEXT", FragmentIdentity.sha256Hex(json(Arrays.asList(
          POLICY,dialect,op.kind().name(),op.nodeType(),op.path(),op.oldText(),op.newText())))));
    }
    return result;
  }
  private static Delta compare(Form a, Form b) {
    if(a.degraded || b.degraded) return new Delta(Set.of(),true,"CANONICAL_UNAVAILABLE","UNAVAILABLE");
    if(!a.policy.equals(b.policy) || !a.dialect.equals(b.dialect))
      return new Delta(Set.of(),true,"IDENTITY_CONTEXT_MISMATCH","UNAVAILABLE");
    var result = AstLcsDiffer.diffResult(a.tokens, b.tokens);
    if (!result.degraded() && (result.ops().isEmpty() != a.canonical.equals(b.canonical)))
      throw new IllegalStateException("DIFF_CANONICAL_INVARIANT_BROKEN");
    return new Delta(result.ops(), result.degraded(), result.degraded() ? "LCS_BUDGET_EXCEEDED" : null, result.method());
  }
}
