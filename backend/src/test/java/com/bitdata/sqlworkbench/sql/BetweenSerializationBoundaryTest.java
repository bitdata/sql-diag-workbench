package com.bitdata.sqlworkbench.sql;

import com.bitdata.sqlworkbench.connection.DatabaseStrategyFactory;
import com.bitdata.sqlworkbench.sql.decompose.*;
import com.bitdata.sqlworkbench.sql.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BetweenSerializationBoundaryTest {
    final SqlDialectResolver resolver=new SqlDialectResolver(DatabaseStrategyFactory.withBuiltinStrategies());
    final SqlParseEngine parser=new SqlParseEngine(resolver);
    final DecompositionRuleEngine engine=new DecompositionRuleEngine(new ClauseStrippingRule(resolver),new SplitSetOpRule(resolver),new PredicateDecompositionRule(resolver),new CteExpansionRule(resolver),new JoinSplitRule(resolver),new SubqueryExtractionRule(resolver),resolver);
    final SqlParseService service=new SqlParseService(parser,new DecompositionService(engine),resolver);
    final SqlParseController controller=new SqlParseController(service,new SqlPositionService(resolver));
    static final String UNSUPPORTED="MYSQL_BETWEEN_SYMMETRIC_UNSUPPORTED";

    @Test void rootUnsupportedIs422AndNotACompleteOrExecutableTree() {
        var response=controller.parse(new ParseRequest("SELECT x FROM t WHERE x BETWEEN SYMMETRIC 3 AND 1","mysql"));
        assertEquals(422,response.getStatusCode().value());
        var result=(ParseResult)response.getBody();
        assertNull(result.tree());
        assertEquals(SqlSerializationException.CODE,result.errors().get(0).code());
        assertEquals(UNSUPPORTED,result.errors().get(0).message());
        assertEquals("FAILED",result.scan().status());
    }

    @Test void optionsAndDetailedExposeTypedReasonThroughRuleWrapper() throws Exception {
        String sql="SELECT x FROM t WHERE x BETWEEN SYMMETRIC 3 AND 1 ORDER BY x";
        var options=service.scanOptions(sql,"mysql");
        assertNotEquals("COMPLETE",options.scan().status());
        assertTrue(options.scan().issues().stream().anyMatch(i->i.code().equals(SqlSerializationException.CODE)&&i.message().equals(UNSUPPORTED)));
        var batch=service.decomposeDetailed(sql,"mysql","clause-stripping","source");
        assertEquals("FAILED",batch.status());
        assertTrue(batch.children().isEmpty());
        assertEquals(SqlSerializationException.CODE,batch.issues().get(0).code());
        assertEquals(UNSUPPORTED,batch.issues().get(0).message());
        var failure=assertThrows(IllegalStateException.class,()->service.decompose(sql,"mysql","clause-stripping"));
        assertEquals(UNSUPPORTED,SqlSerializationException.find(failure).reason());
    }

    @Test void compositionDiffDoesNotReportUnsupportedAsAnEmptySuccess() {
        String parent="SELECT a.id FROM t a JOIN u b ON a.id BETWEEN SYMMETRIC b.lo AND b.hi";
        var joins=service.joinDiff(new JoinDiffRequest(parent,"SELECT a.id FROM t a","mysql"));
        assertTrue(joins.degraded());assertEquals(UNSUPPORTED,joins.errorReason());
        var where=service.fragmentDiff(new FragmentDiffRequest("where","SELECT x FROM t WHERE x BETWEEN SYMMETRIC 3 AND 1","SELECT x FROM t","mysql"));
        assertTrue(where.degraded());assertEquals(UNSUPPORTED,where.errorReason());
    }

    @ParameterizedTest @ValueSource(strings={"mysql","postgresql"})
    void allSixRulesRetainBetweenInExecutableCandidates(String dialect) throws Exception {
        Map<String,String> cases=new LinkedHashMap<>();
        cases.put("clause-stripping","SELECT t.id FROM t WHERE t.id BETWEEN 1 AND 3 ORDER BY t.id");
        cases.put("predicate-decomposition","SELECT t.id FROM t WHERE t.id BETWEEN 1 AND 3 AND t.id > 0");
        cases.put("join-split","SELECT a.id FROM t a JOIN u b ON a.id=b.id WHERE a.id BETWEEN 1 AND 3");
        cases.put("set-op-split","SELECT t.id FROM t WHERE t.id BETWEEN 1 AND 3 UNION ALL SELECT t.id FROM t WHERE t.id BETWEEN 2 AND 4");
        cases.put("cte-expansion","WITH bounded AS (SELECT t.id FROM t WHERE t.id BETWEEN 1 AND 3) SELECT id FROM bounded");
        cases.put("subquery-extraction","SELECT d.id FROM (SELECT t.id FROM t WHERE t.id BETWEEN 1 AND 3) d");
        for(var entry:cases.entrySet()) {
            var batch=service.decomposeDetailed(entry.getValue(),dialect,entry.getKey(),"source");
            assertTrue(batch.children().stream().anyMatch(c->c.sqlFragment().contains("BETWEEN")),entry.getKey()+": "+batch);
            for(var child:batch.children()) {
                assertTrue(child.executable());
                if(dialect.equals("mysql"))assertFalse(child.sqlFragment().contains("ASYMMETRIC"),child.sqlFragment());
                assertNotNull(parser.parse(child.sqlFragment(),dialect));
                assertNotNull(child.canonicalSql());
            }
        }
    }

    @Test void sourceIdentityAndUnrelatedDependencyInspectionRemainStable() throws Exception {
        String source="SELECT t.id FROM t WHERE t.id BETWEEN 1 AND 3";
        var root=service.parse(new ParseRequest(source,"mysql")).tree();
        var repeat=service.parse(new ParseRequest(root.sqlFragment(),"mysql")).tree();
        assertEquals(root.id(),repeat.id());assertNull(root.canonicalizationError());
        var deps=service.getCteDependencies("WITH c AS (SELECT x FROM t WHERE x BETWEEN SYMMETRIC 3 AND 1) SELECT x FROM c","mysql");
        assertEquals(1,deps.ctes().size());
    }
}
