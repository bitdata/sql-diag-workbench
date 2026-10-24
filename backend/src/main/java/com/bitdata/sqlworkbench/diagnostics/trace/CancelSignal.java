package com.bitdata.sqlworkbench.diagnostics.trace;
/** Diagnostic receipt only: SENT does not prove the database stopped executing. */
public record CancelSignal(String state, String errorReason) {}
