package com.bitdata.sqlworkbench.diagnostics.source;

import com.bitdata.sqlworkbench.diagnostics.trace.*;
import com.bitdata.sqlworkbench.sql.diff.StructuralSqlCodec;
import com.bitdata.sqlworkbench.sql.model.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import java.util.*;

/** Short-lived server-certified source SQL. No client supplied temporary graph is trusted. */
@Component
public final class SourceObservationReceipts {
    private record Receipt(DiagnosticContext context,String connectionId,String connectionVersion,String sql,
                           List<KeyValue> keys,long expires) {}
    private final Map<String,Receipt> receipts=new LinkedHashMap<>();
    private final DiagnosticAdmission admission;
    private final ObjectMapper json;
    private final java.util.function.LongSupplier clock;
    @org.springframework.beans.factory.annotation.Autowired
    public SourceObservationReceipts(DiagnosticAdmission admission,ObjectMapper json){this(admission,json,System::nanoTime);}
    SourceObservationReceipts(DiagnosticAdmission admission,ObjectMapper json,java.util.function.LongSupplier clock){this.admission=admission;this.json=json;this.clock=clock;}
    public synchronized String issue(PhysicalSourcePlanService.Request request,String sql,List<PhysicalSourcePlanService.BoundKey> keys,String version) {
        admission.revalidate(request.diagnosticContext(),request.connectionId(),version);
        long now=clock.getAsLong();receipts.values().removeIf(r->now>=r.expires());
        if(receipts.size()>=256)throw DiagnosticAdmission.failure(429,"SOURCE_RECEIPT_CAPACITY");
        String id=UUID.randomUUID().toString();
        receipts.put(id,new Receipt(request.diagnosticContext(),request.connectionId(),version,sql,
            keys.stream().map(k->new KeyValue(k.column(),k.value(),k.type())).toList(),now+120_000_000_000L));
        return id;
    }
    public synchronized TraceRequest resolve(TraceRequest request,String dialect) {
        admission.require(request.diagnosticContext(),request.connectionId());
        Receipt receipt=receipts.get(request.sourceReceiptId());
        if(receipt==null)throw DiagnosticAdmission.failure(404,"SOURCE_RECEIPT_UNAVAILABLE");
        if(!receipt.context().equals(request.diagnosticContext())||!receipt.connectionId().equals(request.connectionId()))
            throw DiagnosticAdmission.failure(409,"SOURCE_RECEIPT_SCOPE_MISMATCH");
        if(clock.getAsLong()>=receipt.expires()){receipts.remove(request.sourceReceiptId());throw DiagnosticAdmission.failure(410,"SOURCE_RECEIPT_EXPIRED");}
        admission.revalidate(receipt.context(),receipt.connectionId(),receipt.connectionVersion());
        var form=StructuralSqlCodec.canonicalize(receipt.sql(),dialect);
        if(form.degraded())throw DiagnosticAdmission.failure(422,"SOURCE_CANONICAL_UNAVAILABLE");
        var node=new FragmentNode(form.id(),FragmentType.ORIGINAL,"Independent physical source",receipt.sql(),true,null,0,null,List.of(),List.of(),null,SourceKind.AUTO,form.canonical(),null,null,null,null);
        try {
            // Internally generated tree remains distinct from graph admission; context preserved for execution revalidation.
            return new TraceRequest(request.connectionId(),json.writeValueAsString(node),receipt.keys(),request.queryTimeoutSeconds(),TraceMode.CARDINALITY,null,List.of(),request.diagnosticContext(),null);
        }catch(com.fasterxml.jackson.core.JsonProcessingException e){throw DiagnosticAdmission.failure(500,"SOURCE_ENCODING_FAILED");}
    }
}
