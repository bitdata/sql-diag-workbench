package com.bitdata.sqlworkbench.diagnostics.trace;

import com.bitdata.sqlworkbench.connection.*;
import com.bitdata.sqlworkbench.diagnostics.RuleRegistry;
import com.bitdata.sqlworkbench.sql.*;
import com.bitdata.sqlworkbench.sql.decompose.*;
import com.bitdata.sqlworkbench.sql.model.*;
import com.bitdata.sqlworkbench.sql.model.DecompositionResult.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SharedDagContractTest {
    final SqlDialectResolver resolver=RuleTestDeps.testResolver();
    TraceStore store; RowTraceEngine engine; ConnectionService service; Connection jdbc;
    @BeforeEach void setup() throws Exception {
        store=new TraceStore(); service=mock(ConnectionService.class);
        when(service.getById("c")).thenReturn(Optional.of(new ConnectionConfig("c","test","",0,"","","postgresql","",false,"","",null,false)));
        when(service.openJdbcConnection("c")).thenAnswer(i -> jdbc=spy(DriverManager.getConnection("jdbc:sqlite::memory:")));
        var factory=mock(DatabaseStrategyFactory.class); var adapter=mock(DatabaseStrategy.class);
        when(adapter.getDialectProfile()).thenReturn(new PostgreSqlStrategy().getDialectProfile());
        when(adapter.dialect()).thenReturn("postgresql"); when(factory.get("postgresql")).thenReturn(adapter);
        engine=new RowTraceEngine(service,factory,store,mock(RuleRegistry.class),new SelectOnlyGuard(resolver),new SqlParseEngine(resolver),5);
    }
    @AfterEach void close() {engine.shutdown();store.shutdown();}
    FragmentNode n(String id,FragmentNode... children) {return sql(id,"SELECT 1 AS ID",children);}
    FragmentNode sql(String id,String sql,FragmentNode... children) {
        return new FragmentNode(id,FragmentType.ORIGINAL,id,sql,true,null,children.length,null,List.of(children));
    }
    FragmentNode copy(FragmentNode n,List<FragmentNode> children) {
        return new FragmentNode(n.id(),n.type(),n.label(),n.sqlFragment(),n.executable(),n.executionHint(),children.size(),
            n.ruleName(),children,n.parentIds(),n.transform(),n.source(),n.canonicalSql(),n.editParentId(),n.createdAt(),n.executionSummary(),n.canonicalizationError());
    }
    Batch decompose(FragmentNode n,String rule) throws Exception {
        var rules=new DecompositionRuleEngine(new ClauseStrippingRule(resolver),new SplitSetOpRule(resolver),new PredicateDecompositionRule(resolver),
            new CteExpansionRule(resolver),new JoinSplitRule(resolver),new SubqueryExtractionRule(resolver),resolver);
        return rules.decomposeDetailed(new SqlParseEngine(resolver).parse(n.sqlFragment(),"postgresql"),n.sqlFragment(),"postgresql",rule,n.id());
    }
    TraceProgress run(FragmentNode root,int key,TraceMode mode,List<Output> outputs) throws Exception {
        var request=new TraceRequest("c",new ObjectMapper().writeValueAsString(root),List.of(new KeyValue("ID",key)),5,mode,null,outputs);
        String id=store.create(request);engine.executeTraceSync(id,request);return store.toProgress(id);
    }
    @Test void asymmetricExpansionMergesEdgesAndOrderingIsIndependentOfPaths() {
        var root=n("R",n("A",n("X")),n("B",n("X",n("Y"))));
        var scope=new TraceInputValidator().validate(root);
        assertEquals(Set.of("Y"),scope.children().get("X"));
        assertEquals(List.of("Y","X","A","B","R"),scope.leafFirst());
        assertEquals(6,scope.occurrences().size()); assertEquals(5,scope.nodes().size());
        var reversed=copy(root,List.of(root.children().get(1),root.children().get(0)));
        assertEquals(scope.leafFirst(),new TraceInputValidator().validate(reversed).leafFirst());
        assertThrows(UnsupportedOperationException.class,()->scope.children().get("X").clear());
    }
    @Test void mergedCycleAndEveryIdentityConflictFailBothModesBeforeJdbc() throws Exception {
        var bad=new ArrayList<FragmentNode>();
        bad.add(n("R",n("A",n("X",n("Y"))),n("B",n("Y",n("X")))));
        bad.add(n("R",n("R"))); bad.add(n("R",n("")));
        var a=n("same");
        bad.add(n("R",a,sql("same","SELECT 2 AS ID")));
        bad.add(n("R",a,new FragmentNode("same",a.type(),"x",a.sqlFragment(),false,null,0,null,List.of())));
        bad.add(n("R",a,new FragmentNode("same",a.type(),"x",a.sqlFragment(),true,null,0,null,List.of(),List.of(),null,a.source(),"canonical",null,null,null,null)));
        bad.add(n("R",a,new FragmentNode("same",a.type(),"x",a.sqlFragment(),true,null,0,null,List.of(),List.of(),null,a.source(),null,null,null,null,"parse failure")));
        bad.add(new FragmentNode("R",a.type(),"R",a.sqlFragment(),true,null,0,null,List.of(),List.of("parent"),null,a.source(),null,null,null,null,null));
        bad.add(copy(n("R"),Arrays.asList((FragmentNode)null)));
        for(var root:bad) for(var mode:TraceMode.values()) {
            var result=run(root,1,mode,List.of()); assertEquals(TraceStatus.FAILED,result.status());
            String expected=root==bad.get(0)||root==bad.get(1)?"TRACE_INPUT_CYCLE":
                bad.indexOf(root)>=3&&bad.indexOf(root)<=6?"TRACE_NODE_CONFLICT":"TRACE_INPUT_INVALID";
            assertEquals(expected,result.errorReason());assertTrue(result.steps().isEmpty());
        }
        verify(service,never()).openJdbcConnection(anyString());
    }
    @Test void inclusiveUniqueAndOccurrenceCaps() {
        var validator=new TraceInputValidator();var children=new ArrayList<FragmentNode>();
        for(int i=0;i<63;i++) children.add(n("n"+i));
        assertEquals(64,validator.validate(copy(n("R"),children)).nodes().size());children.add(n("n63"));
        assertEquals("TRACE_SCOPE_TOO_LARGE",assertThrows(TraceInputValidator.Invalid.class,()->validator.validate(copy(n("R"),children))).code);
        var repeated=new ArrayList<FragmentNode>(Collections.nCopies(511,n("X")));
        assertEquals(512,validator.validate(copy(n("R"),repeated)).occurrences().size()); repeated.add(n("X"));
        assertEquals("TRACE_SCOPE_TOO_LARGE",assertThrows(TraceInputValidator.Invalid.class,()->validator.validate(copy(n("R"),repeated))).code);
        FragmentNode deep=n("n0");for(int i=1;i<64;i++) deep=n("n"+i,deep);
        assertEquals(64,validator.validate(deep).leafFirst().size());
    }
    @Test void rejectedCapacityNeverOpensJdbcInEitherMode() throws Exception {
        var wide=new ArrayList<FragmentNode>();for(int i=0;i<64;i++) wide.add(n("n"+i));
        for(var root:List.of(copy(n("R"),wide),copy(n("R"),Collections.nCopies(512,n("X")))))
            for(var mode:TraceMode.values()) {
                var result=run(root,1,mode,List.of());
                assertEquals("TRACE_SCOPE_TOO_LARGE",result.errorReason());assertTrue(result.steps().isEmpty());
            }
        verify(service,never()).openJdbcConnection(anyString());
    }
    @Test void actualRuleDiamondHasFourObservationsEightJdbcStatementsAndTwoCandidates() throws Exception {
        var root=sql("root","SELECT t.ID FROM (SELECT 1 AS ID UNION ALL SELECT 2 AS ID UNION ALL SELECT 3 AS ID) t WHERE t.ID > 1 ORDER BY t.ID");
        var first=decompose(root,"clause-stripping"); var children=new ArrayList<FragmentNode>();var outputs=new ArrayList<>(first.outputs());
        for(var child:first.children()) {var batch=decompose(child,"clause-stripping");children.add(copy(child,batch.children()));outputs.addAll(batch.outputs());}
        root=copy(root,children);
        for(int key:new int[]{1,3,99}) {
            var result=run(root,key,TraceMode.EXISTENCE,outputs);
            assertEquals(TraceStatus.COMPLETED,result.status(),result.error()); assertEquals(4,result.steps().size());
            verify(jdbc,times(8)).prepareStatement(anyString());
            assertEquals(4,result.evidence().totalNodes());assertEquals(4,result.evidence().observedNodes());
            assertTrue(result.evidence().limitations().stream().anyMatch(l->l.code().equals("MULTI_STATEMENT_SNAPSHOT_UNVERIFIED")));
            if(key==1) {
                assertInstanceOf(EliminationOutcome.Edge.class,result.eliminationOutcome());
                assertTrue(result.evidence().limitations().stream().anyMatch(l->l.code().equals("MULTIPLE_ELIMINATION_EDGES")&&l.message().startsWith("2 ")));
            } else if(key==3) assertInstanceOf(EliminationOutcome.None.class,result.eliminationOutcome());
            else assertInstanceOf(EliminationOutcome.Inconclusive.class,result.eliminationOutcome());
        }
        assertInstanceOf(EliminationOutcome.Inconclusive.class,run(root,1,TraceMode.EXISTENCE,List.of()).eliminationOutcome());
    }
    @Test void duplicateUnionUsesVerifiedOrdinalsEvenWithUnrelatedRepresentativeRule() throws Exception {
        var root=sql("root","SELECT 1 AS ID UNION ALL SELECT 1 AS ID");var batch=decompose(root,"set-op-split");
        var child=batch.children().get(0);
        child=new FragmentNode(child.id(),child.type(),child.label(),child.sqlFragment(),true,null,0,"unrelated-rule",List.of());
        root=copy(root,List.of(child,child));
        var result=run(root,1,TraceMode.CARDINALITY,batch.outputs());
        assertEquals(TraceStatus.COMPLETED,result.status(),result.error());assertEquals("2",result.quantity().rootCount());
        assertEquals(2,result.steps().size());verify(jdbc,times(4)).prepareStatement(anyString());
        var positions=result.quantity().comparisons().get(0).observations();
        assertEquals(List.of(1,2),positions.stream().map(QuantityEvidence.Observation::ordinal).toList());
        assertFalse(positions.get(0).reusedObservation());assertTrue(positions.get(1).reusedObservation());
        var onlyFirst=run(root,1,TraceMode.CARDINALITY,List.of(batch.outputs().get(0)));
        assertTrue(onlyFirst.quantity().comparisons().isEmpty());
        assertTrue(onlyFirst.evidence().limitations().stream().anyMatch(l->l.code().equals("BRANCH_COVERAGE_INCOMPLETE")));
    }
    @Test void queuedCancellationNeverDispatchesAndExistenceHasNoCountBudget() throws Exception {
        var clock=new java.util.concurrent.atomic.AtomicLong();var timed=new TraceStore(clock::get);
        try {var request=new TraceRequest("c","{}",List.of(new KeyValue("id",1)),5);
            String id=timed.create(request);clock.set(java.util.concurrent.TimeUnit.SECONDS.toNanos(121));
            assertEquals(5,timed.remainingTimeout(id,5));assertTrue(timed.canExecute(id));
        } finally {timed.shutdown();}
        var request=new TraceRequest("c",new ObjectMapper().writeValueAsString(n("R")),List.of(new KeyValue("id",1)),5);
        String id=store.create(request);store.cancel(id);engine.executeTraceSync(id,request);
        assertEquals(TraceStatus.CANCELLED,store.toProgress(id).status());verify(service,never()).openJdbcConnection(anyString());
    }
    @Test void pairAuthorizationCannotSubstituteForRulePositionAuthorization() {
        var root=sql("root","SELECT 1 AS ID UNION ALL SELECT 1 AS ID",sql("branch","SELECT 1 AS ID"));
        var scope=new TraceInputValidator().validate(root);
        var steps=List.of(new TraceStep(0,"root","root","",TraceStatus.HAS_ROWS,-1,0,null).withObservation(root.sqlFragment(),"2"),
            new TraceStep(1,"branch","branch","",TraceStatus.HAS_ROWS,-1,0,null).withObservation("SELECT 1 AS ID","1"));
        var wrong=new TransformVerifier.Verified(Set.of(new TransformVerifier.Edge("root","branch")),
            Set.of(new TransformVerifier.Position("root","branch","clause-stripping",1),
                new TransformVerifier.Position("root","branch","clause-stripping",2)),List.of());
        assertTrue(new CardinalityStructureInspector(resolver).inspect(scope,steps,List.of(new KeyValue("ID",1)),"postgresql",wrong).comparisons().isEmpty());
        assertFalse(new TransformVerifier.Verified(Set.of(new TransformVerifier.Edge("a::b","c")),Set.of(),List.of()).allows("a","b::c"));
    }
    @Test void forgedOrStaleProvenanceRetainsFactsWithoutComparison() throws Exception {
        var root=sql("root","SELECT 1 AS ID UNION ALL SELECT 1 AS ID");var batch=decompose(root,"set-op-split");
        root=copy(root,batch.children());var verifier=new TransformVerifier(resolver);var scope=new TraceInputValidator().validate(root);
        for(String variant:List.of("source","target","ordinal","context","sql")) {
            List<Output> forged=new ArrayList<>();
            for(Output o:batch.outputs()) {
                var e=o.effect();
                var changed=new Effect(variant.equals("source")?"forged":e.sourceSqlHash(),variant.equals("target")?"stale":e.targetSqlHash(),
                    variant.equals("context")?"UNKNOWN":e.context(),e.changes(),e.retained(),e.limitations());
                forged.add(new Output(o.sourceNodeId(),o.ruleName(),o.targetNodeId(),variant.equals("ordinal")?999:o.ordinal(),changed));
            }
            var nodes=scope.nodes();
            if(variant.equals("sql")) {var changed=new LinkedHashMap<>(nodes);var child=batch.children().get(0);
                changed.put(root.id(),copy(root,List.of(sql(child.id(),"SELECT 2 AS ID"))));nodes=changed;}
            var verified=verifier.verify(nodes,forged,"postgresql",()->true);
            assertTrue(verified.comparable().isEmpty(),variant);assertTrue(verified.positions().isEmpty(),variant);
            assertFalse(verified.limitations().isEmpty(),variant);
        }
        assertTrue(verifier.verify(scope.nodes(),batch.outputs(),"postgresql",()->false).positions().isEmpty());
    }
}
