package com.bitdata.sqlworkbench.diagnostics;

import java.time.Instant;

/**
 * A persisted diagnostic report for a fragment analysis.
 *
 * @param id          unique identifier (UUID)
 * @param sessionId   the session this report belongs to
 * @param fragmentId  the fragment that was analyzed
 * @param findingsJson JSON-serialized list of DiagnosticFinding
 * @param createdAt   when the report was created
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
public record DiagnosticReport(
    String id,
    String sessionId,
    String fragmentId,
    String findingsJson,
    Instant createdAt,
    String evaluationJson
) {
    public DiagnosticReport(String id,String sessionId,String fragmentId,String findingsJson,Instant createdAt) {
        this(id,sessionId,fragmentId,findingsJson,createdAt,null);
    }
}
