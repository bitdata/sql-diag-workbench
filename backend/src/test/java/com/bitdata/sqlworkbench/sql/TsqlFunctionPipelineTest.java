package com.bitdata.sqlworkbench.sql;

import com.bitdata.sqlworkbench.connection.DatabaseStrategyFactory;
import com.bitdata.sqlworkbench.sql.decompose.*;
import com.bitdata.sqlworkbench.sql.diff.StructuralSqlCodec;
import com.bitdata.sqlworkbench.sql.model.ParseRequest;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TsqlFunctionPipelineTest {
    private final SqlDialectResolver resolver=new SqlDialectResolver(DatabaseStrategyFactory.withBuiltinStrategies());
    private final ClauseStrippingRule clause=new ClauseStrippingRule(resolver);
    private final SplitSetOpRule set=new SplitSetOpRule(resolver);
    private final PredicateDecompositionRule predicate=new PredicateDecompositionRule(resolver);
    private final CteExpansionRule cte=new CteExpansionRule(resolver);
    private final JoinSplitRule join=new JoinSplitRule(resolver);
    private final SubqueryExtractionRule subquery=new SubqueryExtractionRule(resolver);
    private final DecompositionRuleEngine engine=new DecompositionRuleEngine(clause,set,predicate,cte,join,subquery,resolver);
    @Test void allSixRulesShareTheExecutableWriterWithoutChangingIdentity() throws Exception {
        List<DecompositionRule> rules=List.of(clause,set,predicate,cte,join,subquery);
        var inputs=List.of(
            "SELECT COUNT_BIG(*) AS n FROM Sales.SalesOrderDetail WHERE OrderQty > 1",
            "SELECT COUNT_BIG(*) AS n FROM Sales.SalesOrderDetail UNION ALL SELECT COUNT_BIG(*) AS n FROM Production.Product",
            "SELECT LEN(ProductNumber) AS n FROM Production.Product WHERE ProductID > 5 AND ProductID < 20",
            "WITH c AS (SELECT COUNT_BIG(*) AS n FROM Sales.SalesOrderDetail) SELECT n FROM c",
            "SELECT a.SalesOrderID,LEN(p.Name) AS n FROM Sales.SalesOrderDetail a JOIN Production.Product p ON a.ProductID=p.ProductID",
            "SELECT q.n FROM (SELECT COUNT_BIG(*) AS n FROM Sales.SalesOrderDetail) q");
        var coverage=new HashMap<String,Integer>();
        for(String sql:inputs) {
            var ast=StructuralSqlCodec.parse(sql,"sqlserver");
            var root=engine.createRootNode(ast,"sqlserver");
            assertFalse(root.sqlFragment().contains("[COUNT_BIG]"));assertFalse(root.sqlFragment().contains("[LEN]"));
            for(var rule:rules) {
                var batch=engine.decomposeDetailed(ast,sql,"sqlserver",rule.name(),null);
                assertTrue(List.of("COMPLETE","NOT_APPLICABLE").contains(batch.status()),batch.toString());
                assertTrue(batch.issues().isEmpty(),batch.toString());
                coverage.merge(rule.name(),batch.children().size(),Integer::sum);
                for(var child:batch.children()) {
                    assertFalse(child.sqlFragment().contains("[COUNT_BIG]"));assertFalse(child.sqlFragment().contains("[LEN]"));
                    var canonical=StructuralSqlCodec.canonicalize(child.sqlFragment(),"sqlserver");
                    assertFalse(canonical.degraded());assertEquals(child.id(),canonical.id());
                }
            }
        }
        assertEquals(6,coverage.size());assertTrue(coverage.values().stream().allMatch(n->n>0));
    }
    @Test void unsupportedFunctionShapeIsObservableInRootScanAndDetailedOutput() throws Exception {
        String sql="SELECT COUNT_BIG(DISTINCT x) OVER(PARTITION BY x) AS n FROM t WHERE x>1";
        var ast=StructuralSqlCodec.parse(sql,"sqlserver");
        var batch=engine.decomposeDetailed(ast,sql,"sqlserver",clause.name(),null);
        assertNotEquals("COMPLETE",batch.status());assertTrue(batch.children().isEmpty());
        assertTrue(batch.issues().toString().contains("COUNT_BIG_DISTINCT_WINDOW_UNSUPPORTED"),batch.toString());
        assertTrue(batch.issues().stream().allMatch(i->i.code().equals(SqlSerializationException.CODE)));
        var scan=engine.scanOptions(ast,"sqlserver");
        assertNotEquals("COMPLETE",scan.scan().status());
        assertTrue(scan.scan().issues().toString().contains("COUNT_BIG_DISTINCT_WINDOW_UNSUPPORTED"),scan.toString());
        var service=new SqlParseService(new SqlParseEngine(resolver),new DecompositionService(engine),resolver);
        var response=new SqlParseController(service,null).parse(new ParseRequest(sql,"sqlserver"));
        assertTrue(response.getStatusCode().is4xxClientError());
        assertTrue(response.getBody().toString().contains("COUNT_BIG_DISTINCT_WINDOW_UNSUPPORTED"));
    }
}
