package com.bitdata.sqlworkbench.diagnostics.trace;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class DiagnosticLifecycleWireTest {
    @Test void bothOracleChannelsPreserveExplicitNullableLifecycleReasonUnderProductionJacksonPolicy() throws Exception {
        var json = new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);
        var lifecycle = new TraceLifecycle(1, 1, true, "NOT_REQUIRED", "CLOSED", null, false,
            Map.of("state", "CONFIRMED"), "UNKNOWN", "UNCONFIRMED");
        var trace = new TraceProgress("t", TraceStatus.COMPLETED, java.util.List.of(), null, java.util.List.of(),
            1, null, null, null, null, TraceMode.EXISTENCE, null, null, "GRAPH", lifecycle);
        var preparation = new SourcePreparationService.Progress("p", SourcePreparationService.Kind.DIRECT,
            TraceStatus.COMPLETED, null, lifecycle, null);
        for (Object response : java.util.List.of(trace, preparation)) {
            var wire = json.valueToTree(response);
            assertTrue(wire.path("lifecycle").has("errorReason"), "Strict renderer decoder requires an explicit nullable field");
            assertTrue(wire.path("lifecycle").path("errorReason").isNull());
        }
        var nonOracle = new TraceProgress("t", TraceStatus.COMPLETED, java.util.List.of(), null,
            java.util.List.of(), 1, null, null, null);
        assertFalse(json.valueToTree(nonOracle).has("lifecycle"));
    }
}
