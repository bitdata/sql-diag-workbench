package com.bitdata.sqlworkbench.diagnostics.source;
import com.bitdata.sqlworkbench.diagnostics.trace.*;
import com.bitdata.sqlworkbench.session.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SourceObservationReceiptsTest {
    final DiagnosticContext context=new DiagnosticContext("s","structural-p0p1-v2",0L,"a".repeat(32));
    final PhysicalSourcePlanService.Request request=new PhysicalSourcePlanService.Request("c","SELECT 1 AS k",List.of(new KeyValue("k","1",KeyValue.Type.DECIMAL)),context,null);
    TraceRequest observe(String id){return new TraceRequest("c",null,null,30,TraceMode.CARDINALITY,null,null,context,id);}
    @Test void ttlIsEnforcedAtBoundaryAndExpiredEntriesReleaseCapacity() {
        var clock=new AtomicLong();var admission=mock(DiagnosticAdmission.class);
        var receipts=new SourceObservationReceipts(admission,new ObjectMapper(),clock::get);
        var keys=List.of(new PhysicalSourcePlanService.BoundKey("k","1",KeyValue.Type.DECIMAL));
        String first=receipts.issue(request,"SELECT 1 AS k",keys,"v");
        for(int i=1;i<256;i++)receipts.issue(request,"SELECT 1 AS k",keys,"v");
        assertEquals("SOURCE_RECEIPT_CAPACITY",assertThrows(WorkspaceIdentity.Failure.class,()->receipts.issue(request,"SELECT 1 AS k",keys,"v")).code);
        clock.set(119_999_999_999L);assertEquals("1",receipts.resolve(observe(first),"sqlserver").keys().get(0).value());
        clock.incrementAndGet();assertEquals("SOURCE_RECEIPT_EXPIRED",assertThrows(WorkspaceIdentity.Failure.class,()->receipts.resolve(observe(first),"sqlserver")).code);
        assertNotNull(receipts.issue(request,"SELECT 1 AS k",keys,"v"));
        assertEquals("SOURCE_RECEIPT_UNAVAILABLE",assertThrows(WorkspaceIdentity.Failure.class,()->new SourceObservationReceipts(admission,new ObjectMapper()).resolve(observe(first),"sqlserver")).code);
    }
    @Test void receiptCannotMoveAcrossConnectionOrWorkspace() {
        var admission=mock(DiagnosticAdmission.class);var receipts=new SourceObservationReceipts(admission,new ObjectMapper());
        String receipt=receipts.issue(request,"SELECT 1 AS k",List.of(new PhysicalSourcePlanService.BoundKey("k","1",KeyValue.Type.DECIMAL)),"v");
        for(var submission:List.of(new TraceRequest("other",null,null,30,TraceMode.CARDINALITY,null,null,context,receipt),
            new TraceRequest("c",null,null,30,TraceMode.CARDINALITY,null,null,new DiagnosticContext("s",context.identityPolicy(),1L,context.workspaceInstanceId()),receipt)))
            assertEquals("SOURCE_RECEIPT_SCOPE_MISMATCH",assertThrows(WorkspaceIdentity.Failure.class,()->receipts.resolve(submission,"sqlserver")).code);
    }
}
