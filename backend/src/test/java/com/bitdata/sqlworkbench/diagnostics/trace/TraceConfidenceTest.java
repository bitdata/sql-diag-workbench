package com.bitdata.sqlworkbench.diagnostics.trace;

import com.bitdata.sqlworkbench.connection.*;
import com.bitdata.sqlworkbench.sql.model.*;
import org.junit.jupiter.api.Test;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TraceConfidenceTest {
    @Test void localEdgeSurvivesOtherFailuresAndEvidenceRetainsEveryLimit() {
        FragmentNode leaf=new FragmentNode("leaf",FragmentType.ORIGINAL,"leaf","SELECT 1",true,null,0,null,List.of());
        FragmentNode error=new FragmentNode("error",FragmentType.ORIGINAL,"error","SELECT 1",true,null,0,null,List.of());
        FragmentNode root=new FragmentNode("root",FragmentType.ORIGINAL,"root","SELECT 1",true,null,2,null,List.of(leaf,error));
        var steps=List.of(new TraceStep(0,"leaf","leaf","",TraceStatus.HAS_ROWS,-1,1,null),
            new TraceStep(1,"error","error","",TraceStatus.ERROR,-1,1,"Database error"),
            new TraceStep(2,"root","root","",TraceStatus.NO_ROWS,-1,1,null));
        assertInstanceOf(EliminationOutcome.Edge.class,new EliminationDetector().findEliminationOutcome(steps,Map.of("root",root,"leaf",leaf,"error",error),"root"));
        var evidence=TraceEvidence.summarize("root",3,steps,List.of(),List.of(new TraceLimitation("EXPLANATION_FAILED","root",null,"No explanation")));
        assertEquals("PARTIAL",evidence.coverage());
        assertEquals(2,evidence.observedNodes());
        assertTrue(evidence.limitations().stream().anyMatch(l->l.code().equals("STEP_ERROR")));
        assertTrue(evidence.limitations().stream().anyMatch(l->l.code().equals("EXPLANATION_FAILED")));
    }
    @Test void projectionUsesDialectSemanticsAndRequiresEveryKey() throws Exception {
        ResultSetMetaData meta=mock(ResultSetMetaData.class);
        when(meta.getColumnCount()).thenReturn(1); when(meta.getColumnLabel(1)).thenReturn("ID");
        var keys=List.of(new KeyValue("o.id",1));
        var mysql = new MySqlStrategy().getDialectProfile();
        var pg = new PostgreSqlStrategy().getDialectProfile();
        assertEquals(List.of("ID"),TraceKeyProjection.match(meta,keys,mysql).labels());
        assertEquals("KEY_NOT_PROJECTED",TraceKeyProjection.match(meta,keys,pg).reason());
        assertEquals("KEY_NOT_PROJECTED",TraceKeyProjection.match(meta,List.of(new KeyValue("id",1),new KeyValue("cid",2)),mysql).reason());
        when(meta.getColumnCount()).thenReturn(2);when(meta.getColumnLabel(2)).thenReturn("id");
        assertEquals("AMBIGUOUS_KEY",TraceKeyProjection.match(meta,keys,mysql).reason());
    }
    @Test void cancellationFreezesObservationsAndRejectsTheNextStatement() throws Exception {
        TraceStore store=new TraceStore();
        try {
            String id=store.create(new TraceRequest("c","{}",List.of(new KeyValue("id",1)),5));
            store.configureScope(id,"root",2);
            store.addStep(id,new TraceStep(0,"child","child","SELECT 1",TraceStatus.HAS_ROWS,-1,1,null));
            assertTrue(store.cancel(id));
            TraceProgress terminal=store.toProgress(id);
            assertEquals("PARTIAL",terminal.evidence().coverage());
            assertFalse(store.registerActiveStatement(id,mock(Statement.class)));
            store.addStep(id,new TraceStep(1,"root","root","SELECT 1",TraceStatus.NO_ROWS,-1,1,null));
            assertEquals(terminal.steps(),store.toProgress(id).steps());
            assertEquals(terminal.evidence(),store.toProgress(id).evidence());
            assertFalse(store.complete(id,null,List.of(),new EliminationOutcome.None(),null));
        } finally {store.shutdown();}
    }
    @Test void rootPresenceWinsOverASeparateBranchDisappearance() {
        FragmentNode leaf=new FragmentNode("leaf",FragmentType.ORIGINAL,"leaf","SELECT 1",true,null,0,null,List.of());
        FragmentNode branch=new FragmentNode("branch",FragmentType.ORIGINAL,"branch","SELECT 1",true,null,1,null,List.of(leaf));
        FragmentNode root=new FragmentNode("root",FragmentType.ORIGINAL,"root","SELECT 1",true,null,1,null,List.of(branch));
        var steps=List.of(new TraceStep(0,"leaf","leaf","",TraceStatus.HAS_ROWS,-1,1,null),
            new TraceStep(1,"branch","branch","",TraceStatus.NO_ROWS,-1,1,null),
            new TraceStep(2,"root","root","",TraceStatus.HAS_ROWS,-1,1,null));
        assertInstanceOf(EliminationOutcome.None.class,new EliminationDetector().findEliminationOutcome(steps,Map.of("root",root,"branch",branch,"leaf",leaf),"root"));
    }
}
