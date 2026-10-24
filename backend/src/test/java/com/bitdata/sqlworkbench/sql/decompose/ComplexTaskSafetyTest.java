package com.bitdata.sqlworkbench.sql.decompose;

import com.bitdata.sqlworkbench.sql.*;
import com.bitdata.sqlworkbench.sql.model.DecompositionResult.*;
import org.apache.calcite.sql.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Semantic invariants for the M1 task corpus; actual result oracles run on both databases. */
class ComplexTaskSafetyTest {
    final SqlDialectResolver resolver=RuleTestDeps.testResolver();
    final SqlParseEngine parser=new SqlParseEngine(resolver);
    final DecompositionRuleEngine engine=new DecompositionRuleEngine(new ClauseStrippingRule(resolver),new SplitSetOpRule(resolver),new PredicateDecompositionRule(resolver),new CteExpansionRule(resolver),new JoinSplitRule(resolver),new SubqueryExtractionRule(resolver),resolver);
    Batch run(String sql,String dialect,String rule)throws Exception{return engine.decomposeDetailed(parser.parse(sql,dialect),sql,dialect,rule,"source");}
    SqlSelect body(SqlNode n){if(n instanceof SqlOrderBy o)return body(o.query);if(n instanceof SqlWith w)return body(w.body);return (SqlSelect)n;}
    String removed(Batch batch,String alias){return batch.children().stream().filter(n->n.label().matches("(?i)Remove .*\\b"+alias+"(?: —.*)?")).findFirst().orElseThrow(()->new AssertionError(batch.issues().toString())).sqlFragment();}

    @ParameterizedTest @ValueSource(strings={"postgresql","mysql"})
    void cteProjectionNamesDoNotProveBareColumnOwnershipAcrossPhysicalJoin(String dialect)throws Exception{
        String sql="WITH v2 AS (SELECT d.dept_no,d.dept_name,m.emp_no FROM departments d "
            +"JOIN dept_manager m ON m.dept_no=d.dept_no) "
            +"SELECT e.last_name,e.first_name,dept_name,dept_no FROM v2 "
            +"JOIN employees e ON e.emp_no=v2.emp_no WHERE e.gender='F'";
        var scan=engine.scanOptions(parser.parse(sql,dialect),dialect);
        assertEquals("PARTIAL",scan.scan().status());
        assertEquals(2,scan.scan().issues().size());
        assertTrue(scan.scan().issues().stream().allMatch(i->i.code().equals("UNRESOLVED_DEPENDENCY")));
        Batch blocked=run(sql,dialect,"join-split");
        assertEquals("PARTIAL",blocked.status());assertTrue(blocked.children().isEmpty());
        String qualified=sql.replace("e.first_name,dept_name,dept_no", "e.first_name,v2.dept_name,v2.dept_no");
        Batch safe=run(qualified,dialect,"join-split");
        assertEquals("COMPLETE",safe.status(),safe.issues().toString());
        assertEquals(2,safe.children().size());
        for(var child:safe.children())assertTrue(parser.parse(child.sqlFragment(),dialect) instanceof SqlWith);
    }

    @org.junit.jupiter.api.Test
    void mysqlExecutionSpellingSurvivesWhileCanonicalIdentityStaysCompatible() throws Exception {
        String sql="SELECT o.id FROM orders o JOIN tags t ON o.id=t.order_id";
        Batch b=run(sql,"mysql","join-split");
        assertEquals("COMPLETE",b.status());
        assertTrue(removed(b,"T").contains("FROM orders"));
        assertTrue(removed(b,"O").contains("FROM tags"));
        assertEquals(com.bitdata.sqlworkbench.sql.diff.SqlCanonicalizer.canonicalizeSql(sql,"mysql"),
            com.bitdata.sqlworkbench.sql.diff.SqlCanonicalizer.canonicalizeSql(sql.toUpperCase(java.util.Locale.ROOT),"mysql"));
    }

    @ParameterizedTest @ValueSource(strings={"postgresql","mysql"})
    void aggregateProjectionNeverGainsUngroupedJoinKeys(String dialect)throws Exception{
        for(String projection:List.of("o.customer_id,SUM(o.amount) AS total","o.customer_id,COUNT(*) AS n","o.id,COUNT(*) AS n")){
            String group=projection.startsWith("o.id")?"o.id":"o.customer_id";
            Batch b=run("SELECT "+projection+" FROM orders o JOIN tags t ON o.id=t.order_id GROUP BY "+group,dialect,"join-split");
            assertEquals("COMPLETE",b.status(),b.issues().toString());
            SqlSelect s=body(parser.parse(removed(b,"T"),dialect));
            assertEquals(2,s.getSelectList().size(),"No naked ON key may extend the aggregate projection");
            assertEquals(1,s.getGroup().size());
        }
        Batch global=run("SELECT COUNT(*) AS n FROM orders o JOIN tags t ON o.id=t.order_id HAVING COUNT(*)>0",dialect,"join-split");
        assertEquals("COMPLETE",global.status(),global.issues().toString());
        for(var n:global.children())assertEquals(1,body(parser.parse(n.sqlFragment(),dialect)).getSelectList().size());
    }

    @ParameterizedTest @ValueSource(strings={"postgresql","mysql"})
    void danglingHavingAndNamedWindowHaveVisibleCandidateIssues(String dialect)throws Exception{
        Batch star=run("SELECT SUM(o.amount) AS total FROM orders o JOIN tags t ON o.id=t.order_id GROUP BY t.tag",dialect,"join-split");
        assertEquals("PARTIAL",star.status());assertEquals(1,star.children().size());assertEquals(1,star.issues().size());
        assertTrue(star.issues().get(0).sql().contains("*"));
        Batch having=run("SELECT o.customer_id,COUNT(*) AS n FROM orders o JOIN tags t ON o.id=t.order_id GROUP BY o.customer_id HAVING MAX(t.tag)='red'",dialect,"join-split");
        assertEquals("PARTIAL",having.status());assertEquals(1,having.children().size());assertEquals(1,having.issues().size());
        assertEquals(1,body(parser.parse(removed(having,"O"),dialect)).getSelectList().size());
        assertTrue(having.issues().stream().allMatch(i->i.code().equals("UNRESOLVED_DEPENDENCY")&&i.sql()!=null&&i.scopePath().contains("from")));
        Batch window=run("SELECT o.id,ROW_NUMBER() OVER w AS rn FROM orders o JOIN tags t ON o.id=t.order_id WINDOW w AS (PARTITION BY t.tag ORDER BY o.id)",dialect,"join-split");
        assertEquals("PARTIAL",window.status());assertEquals(0,window.children().size());assertEquals(2,window.issues().size());
        assertTrue(window.issues().stream().allMatch(i->i.code().equals("UNRESOLVED_DEPENDENCY")&&i.sql().contains("WINDOW")));
    }

    @ParameterizedTest @ValueSource(strings={"postgresql","mysql"})
    void projectionAliasesAndOrdinalsCannotBindToDifferentOutput(String dialect)throws Exception{
        String q=dialect.equals("mysql")?"`":"\"";
        for(String alias:List.of("customer",q+"Customer"+q)){
            Batch b=run("SELECT o.customer_id AS "+alias+",SUM(o.amount) AS total FROM orders o JOIN tags t ON o.id=t.order_id GROUP BY "+alias,dialect,"join-split");
            assertEquals("PARTIAL",b.status());assertEquals(1,b.children().size());removed(b,"T");
        }
        Batch group=run("SELECT o.customer_id,SUM(o.amount) AS total FROM orders o JOIN tags t ON o.id=t.order_id GROUP BY 1",dialect,"join-split");
        assertEquals("PARTIAL",group.status());assertEquals(1,group.children().size());removed(group,"T");
        Batch order=run("SELECT t.tag,o.id FROM orders o JOIN tags t ON o.id=t.order_id ORDER BY 2",dialect,"join-split");
        assertEquals("PARTIAL",order.status());assertEquals(0,order.children().size());assertEquals(2,order.issues().size());
    }

    @ParameterizedTest @ValueSource(strings={"postgresql","mysql"})
    void shadowedAliasesAndWindowAggregatesRemainUsable(String dialect)throws Exception{
        Batch shadow=run("SELECT o.id,(SELECT MAX(t.amount) FROM orders t) AS largest FROM orders o JOIN tags t ON o.id=t.order_id",dialect,"join-split");
        assertEquals("COMPLETE",shadow.status(),shadow.issues().toString());
        assertTrue(removed(shadow,"T").contains("MAX"),"An inner alias must shadow the removed outer table");
        Batch window=run("SELECT o.id,SUM(o.amount) OVER (ORDER BY o.id) AS running_total FROM orders o JOIN tags t ON o.id=t.order_id",dialect,"join-split");
        assertEquals("COMPLETE",window.status(),window.issues().toString());assertEquals(2,window.children().size());
    }

    @ParameterizedTest @ValueSource(strings={"postgresql","mysql"})
    void joinRetainsAllWithDefinitionsColumnsAndPagination(String dialect)throws Exception{
        Batch b=run("WITH src(id) AS (SELECT 1), unused(n) AS (SELECT 9) SELECT o.id FROM src o JOIN tags t ON o.id=t.order_id ORDER BY o.id LIMIT 1",dialect,"join-split");
        assertEquals("COMPLETE",b.status(),b.issues().toString());
        SqlOrderBy order=(SqlOrderBy)parser.parse(removed(b,"T"),dialect);assertNotNull(order.fetch);assertEquals(1,order.orderList.size());
        SqlWith w=(SqlWith)order.query;assertEquals(2,w.withList.size());
        assertEquals(1,((SqlWithItem)w.withList.get(0)).columnList.size());assertEquals(1,((SqlWithItem)w.withList.get(1)).columnList.size());
    }

    @ParameterizedTest @ValueSource(strings={"postgresql","mysql"})
    void recursiveExtractionReportsBoundaryAndKeepsSafeSibling(String dialect)throws Exception{
        String seq="WITH RECURSIVE seq(n) AS (SELECT 1 UNION ALL SELECT n+1 FROM seq WHERE n<3)";
        for(String sql:List.of(seq+" SELECT n FROM seq",seq+", picked AS (SELECT n FROM seq WHERE n>1) SELECT n FROM picked")){
            Batch b=run(sql,dialect,"cte-expansion");assertEquals("PARTIAL",b.status());assertEquals(0,b.children().size());assertFalse(b.issues().isEmpty());
            assertTrue(b.issues().stream().allMatch(i->i.code().equals("UNRESOLVED_DEPENDENCY")&&i.sql()!=null&&i.scopePath().contains("cte")));
        }
        Batch mixed=run(seq+", safe AS (SELECT 9 AS id) SELECT n FROM seq",dialect,"cte-expansion");
        assertEquals("PARTIAL",mixed.status());assertEquals(1,mixed.children().size());assertTrue(mixed.children().get(0).sqlFragment().contains("9"));
        Batch outer=run(seq+" SELECT n FROM seq WHERE n>1",dialect,"clause-stripping");
        assertEquals("COMPLETE",outer.status());assertTrue(outer.children().get(0).sqlFragment().contains("RECURSIVE"));
        assertEquals(1,((SqlWithItem)((SqlWith)parser.parse(outer.children().get(0).sqlFragment(),dialect)).withList.get(0)).columnList.size());
    }

    @ParameterizedTest @ValueSource(strings={"postgresql","mysql"})
    void dependencyWithRetainsColumnDeclarations(String dialect)throws Exception{
        Batch b=run("WITH src(id) AS (SELECT 1), picked AS (SELECT id FROM src) SELECT id FROM picked",dialect,"cte-expansion");
        assertEquals("COMPLETE",b.status(),b.issues().toString());
        SqlWith with=(SqlWith)parser.parse(b.children().get(0).sqlFragment(),dialect);
        assertNotNull(((SqlWithItem)with.withList.get(0)).columnList);
    }
}
