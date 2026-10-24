package com.bitdata.sqlworkbench.sql.decompose;

import com.bitdata.sqlworkbench.sql.*;
import com.bitdata.sqlworkbench.sql.model.DecompositionResult.*;
import com.bitdata.sqlworkbench.sql.model.FragmentNode;
import com.bitdata.sqlworkbench.diagnostics.trace.TransformVerifier;
import org.apache.calcite.sql.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DecompositionSemanticsTest {
    final SqlDialectResolver resolver=RuleTestDeps.testResolver();
    final SqlParseEngine parser=new SqlParseEngine(resolver);
    final DecompositionRuleEngine engine=new DecompositionRuleEngine(new ClauseStrippingRule(resolver),new SplitSetOpRule(resolver),new PredicateDecompositionRule(resolver),new CteExpansionRule(resolver),new JoinSplitRule(resolver),new SubqueryExtractionRule(resolver),resolver);
    Batch run(String sql,String dialect,String rule)throws Exception { return engine.decomposeDetailed(parser.parse(sql,dialect),sql,dialect,rule,"source"); }
    @ParameterizedTest @ValueSource(strings={"postgresql","mysql"})
    void offsetOnlyFollowsDatabaseSyntax(String dialect)throws Exception {
        Batch b=run("SELECT t.id FROM t ORDER BY t.id OFFSET 1",dialect,"clause-stripping");
        assertEquals(dialect.equals("mysql")?"PARTIAL":"COMPLETE",b.status());
        assertEquals(!dialect.equals("mysql"),b.children().stream().anyMatch(n->n.label().equals("Remove ORDER BY")));
        if(dialect.equals("mysql"))assertTrue(b.issues().stream().anyMatch(i->i.code().equals("UNSUPPORTED_SCOPE")&&i.sql()!=null));
    }
    @ParameterizedTest @ValueSource(strings={"postgresql","mysql"})
    void orderAndPaginationAreIndependent(String dialect)throws Exception {
        for(String suffix:List.of("LIMIT 2 OFFSET 1","LIMIT 0")) {
            Batch b=run("SELECT t.id FROM t ORDER BY t.id "+suffix,dialect,"clause-stripping");
            assertEquals("COMPLETE",b.status());
            FragmentNode noOrder=b.children().stream().filter(n->n.label().equals("Remove ORDER BY")).findFirst().orElseThrow();
            SqlOrderBy ast=(SqlOrderBy)parser.parse(noOrder.sqlFragment(),dialect);
            assertTrue(ast.orderList.isEmpty());assertNotNull(ast.fetch);
            if(suffix.contains("OFFSET"))assertNotNull(ast.offset);
            FragmentNode noLimit=b.children().stream().filter(n->n.label().equals("Remove LIMIT/OFFSET")).findFirst().orElseThrow();
            SqlOrderBy unlimited=(SqlOrderBy)parser.parse(noLimit.sqlFragment(),dialect);
            assertNull(unlimited.fetch);assertFalse(unlimited.orderList.isEmpty());
        }
    }
    @ParameterizedTest @ValueSource(strings={"postgresql","mysql"})
    void cteEditsPreserveSuffixAndSibling(String dialect)throws Exception {
        String sql="WITH a AS (SELECT t.id FROM t WHERE t.id>0 AND t.id<3), b AS (SELECT t.id FROM t WHERE t.id>0 AND t.id<3) SELECT a.id FROM a LIMIT 2";
        for(String rule:List.of("clause-stripping","predicate-decomposition")) {
            Batch b=run(sql,dialect,rule); assertEquals("COMPLETE",b.status());
            for(FragmentNode n:b.children()) {
                if(n.label().equals("Remove LIMIT/OFFSET"))continue;
                SqlOrderBy ob=(SqlOrderBy)parser.parse(n.sqlFragment(),dialect);
                assertNotNull(ob.fetch);
                SqlWith w=(SqlWith)ob.query;
                long original=w.withList.getList().stream().map(x->((SqlSelect)((SqlWithItem)x).query).getWhere()).filter(x->x!=null&&x.getKind()==SqlKind.AND).count();
                assertEquals(1,original,"only the selected CTE changes");
            }
        }
    }
    @ParameterizedTest @ValueSource(strings={"postgresql","mysql"})
    void discoveryShadowingAndDependencyPolicy(String dialect)throws Exception {
        for(String sql:List.of(
            "SELECT c.id, (SELECT SUM(o.amount) FROM orders o WHERE o.cid=c.id) AS total FROM customers c",
            "SELECT c.id, 1+(SELECT SUM(o.amount) FROM orders o WHERE o.cid=c.id) AS total FROM customers c",
            "SELECT a.id FROM users a JOIN (SELECT o.id FROM orders o ORDER BY o.id LIMIT 2) b ON a.id=b.id")) {
            Batch b=run(sql,dialect,"subquery-extraction"); assertEquals("COMPLETE",b.status(),b.issues().toString()); assertEquals(1,b.children().size());
            if(sql.contains("SUM")) { assertEquals("DETACHED",b.outputs().get(0).effect().context()); assertTrue(b.outputs().get(0).effect().changes().stream().anyMatch(c->c.subject().equals("CORRELATED_PREDICATE"))); }
            else assertNotNull(((SqlOrderBy)parser.parse(b.children().get(0).sqlFragment(),dialect)).fetch);
        }
        Batch shadow=run("SELECT a.id FROM users a WHERE EXISTS (SELECT a.id FROM orders a WHERE a.id=1)",dialect,"subquery-extraction");
        assertEquals("COMPLETE",shadow.status());assertTrue(shadow.children().get(0).sqlFragment().contains("WHERE"));assertEquals("PRESERVED",shadow.outputs().get(0).effect().context());
        for(String sql:List.of("SELECT c.id FROM customers c WHERE EXISTS (SELECT c.id FROM orders o)",
            "SELECT c.id FROM customers c WHERE EXISTS (SELECT information_schema.schemata.schema_name FROM information_schema.schemata)",
            "SELECT c.id FROM customers c WHERE EXISTS (SELECT id FROM orders o)",
            "SELECT c.id FROM customers c WHERE EXISTS (SELECT o.id FROM orders o ORDER BY c.id)")) {
            Batch b=run(sql,dialect,"subquery-extraction");assertTrue(b.children().isEmpty());assertEquals("PARTIAL",b.status());assertEquals("UNRESOLVED_DEPENDENCY",b.issues().get(0).code());assertNotNull(b.issues().get(0).sql());
        }
    }
    @ParameterizedTest @ValueSource(strings={"postgresql","mysql"})
    void replayRejectsTamperingAndRetainsRepeatedPositions(String dialect)throws Exception {
        String sql="SELECT 1 AS id UNION ALL SELECT 1 AS id";
        Batch b=run(sql,dialect,"set-op-split");assertEquals(2,b.outputs().size());assertEquals(b.children().get(0).id(),b.children().get(1).id());assertEquals(2,b.outputs().get(1).ordinal());
        FragmentNode root=new FragmentNode("source",com.bitdata.sqlworkbench.sql.model.FragmentType.ORIGINAL,"root",sql,true,null,2,null,b.children());
        Map<String,FragmentNode> nodes=new LinkedHashMap<>();nodes.put(root.id(),root);b.children().forEach(n->nodes.put(n.id(),n));
        TransformVerifier verifier=new TransformVerifier(resolver);
        assertTrue(verifier.verify(nodes,b.outputs(),dialect,()->true).allows("source",b.children().get(0).id()));
        Output o=b.outputs().get(0);Effect e=o.effect();Effect forged=new Effect("bad",e.targetSqlHash(),e.context(),e.changes(),e.retained(),e.limitations());
        assertFalse(verifier.verify(nodes,List.of(new Output(o.sourceNodeId(),o.ruleName(),o.targetNodeId(),1,forged)),dialect,()->true).allows("source",o.targetNodeId()));
        assertFalse(verifier.verify(nodes,List.of(),dialect,()->true).limitations().isEmpty());
        assertEquals("FAILED",run("SELECT 1",dialect,"invalid-rule").status());
        assertEquals("NOT_APPLICABLE",run("SELECT 1",dialect,"clause-stripping").status());
    }
    @ParameterizedTest @ValueSource(strings={"postgresql","mysql"})
    void quotedAliasHasExactScope(String dialect)throws Exception {
        String q=dialect.equals("mysql")?"`":"\"";
        String sql="SELECT "+q+"OuterAlias"+q+".id FROM (SELECT 1 AS id) AS "+q+"OuterAlias"+q+" WHERE EXISTS (SELECT o.id FROM (SELECT 1 AS id) o WHERE o.id="+q+"OuterAlias"+q+".id)";
        Batch b=run(sql,dialect,"subquery-extraction");
        assertEquals("COMPLETE",b.status(),b.issues().toString());
    }
}
