package com.bitdata.sqlworkbench.diagnostics.trace;
/** Additive Oracle lifecycle observation; task completion does not imply resource cleanup. */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
public record TraceLifecycle(int version,long revision,boolean settled,String cancelSignal,String cleanup,String errorReason,
 boolean degraded,java.util.Map<String,Object> resourceObservation,String serverExecution,String applicationCleanup) {}
