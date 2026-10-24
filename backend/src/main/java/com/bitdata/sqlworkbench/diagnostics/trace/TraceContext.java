package com.bitdata.sqlworkbench.diagnostics.trace;

import com.bitdata.sqlworkbench.sql.model.FragmentNode;
import java.util.List;
import java.util.Map;

/** Context passed to diagnostic rules for trace-based analysis. */
public record TraceContext(
    String connectionId,
    FragmentNode rootFragment,
    Map<String, FragmentNode> allNodes,
    TraceResult traceResult,
    List<KeyValue> keys
) {}
