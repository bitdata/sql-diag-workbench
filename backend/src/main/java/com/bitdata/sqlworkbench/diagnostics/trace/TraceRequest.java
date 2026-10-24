package com.bitdata.sqlworkbench.diagnostics.trace;

import java.util.List;

/** Input for a row trace operation. */
public record TraceRequest(
    String connectionId,
    String fragmentTreeJson,
    List<KeyValue> keys,
    Integer queryTimeoutSeconds,
    TraceMode mode,
    @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using=StrictDecimalStringDeserializer.class) String expectedMax,
    List<com.bitdata.sqlworkbench.sql.model.DecompositionResult.Output> transformEdges
) {
    public TraceRequest(String connectionId, String tree, List<KeyValue> keys, Integer timeout) {
        this(connectionId, tree, keys, timeout, null, null, null);
    }
    public TraceRequest(String connectionId,String tree,List<KeyValue> keys,Integer timeout,TraceMode mode,String expectedMax) {
        this(connectionId,tree,keys,timeout,mode,expectedMax,null);
    }
    public TraceRequest {
        if (connectionId == null || connectionId.isBlank()) {
            throw new IllegalArgumentException("connectionId is required");
        }
        if (fragmentTreeJson == null || fragmentTreeJson.isBlank()) {
            throw new IllegalArgumentException("fragmentTreeJson is required");
        }
        if (keys == null || keys.isEmpty()) {
            throw new IllegalArgumentException("At least one key is required");
        }
        keys = List.copyOf(keys);
        transformEdges = transformEdges == null ? List.of() : List.copyOf(transformEdges);
        if(transformEdges.size()>512) throw new IllegalArgumentException("Too many transform outputs");
        mode = mode == null ? TraceMode.EXISTENCE : mode;
        if (expectedMax != null) {
            if (mode != TraceMode.CARDINALITY || !expectedMax.matches("[0-9]{1,256}"))
                throw new IllegalArgumentException("expectedMax requires CARDINALITY and an unsigned integer string");
            try { expectedMax = Long.toString(Long.parseLong(expectedMax)); }
            catch (NumberFormatException invalid) { throw new IllegalArgumentException("expectedMax exceeds signed long range"); }
        }
    }
}
