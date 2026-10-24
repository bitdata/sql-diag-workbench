package com.bitdata.sqlworkbench.sql.diff;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2-13: the diff response must carry the degradation flag + method end-to-end
 * (CLAUDE.md「降级必须可观测」). Covers both the JSON shape (so the frontend
 * can read {@code degraded}/{@code diffMethod}) and the pipeline contract.
 */
class DiffControllerResponseTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void diffResponse_serializesDegradedAndMethodFields() throws Exception {
        DiffController.DiffResponse resp = new DiffController.DiffResponse(
            java.util.List.of(), true, "PATH_SET");

        String json = mapper.writeValueAsString(resp);

        assertTrue(json.contains("\"degraded\":true"), "degraded field must serialize: " + json);
        assertTrue(json.contains("\"diffMethod\":\"PATH_SET\""),
            "diffMethod field must serialize: " + json);
        assertTrue(json.contains("\"ops\":[]"), "ops field must serialize: " + json);
    }

    @Test
    void diffResponse_nonDegradedSerializesFalse() throws Exception {
        DiffController.DiffResponse resp = new DiffController.DiffResponse(
            java.util.List.of(), false, "LCS");

        String json = mapper.writeValueAsString(resp);

        assertTrue(json.contains("\"degraded\":false"), "degraded=false must serialize: " + json);
        assertTrue(json.contains("\"diffMethod\":\"LCS\""), "method LCS must serialize: " + json);
    }

    /** Small predicate change: pipeline must report LCS, not degraded. */
    @Test
    void attributeChangeDetailed_smallChange_notDegradedLcs() throws Exception {
        DiffResult result = ChangeAttribution.attributeChangeDetailed(
            "SELECT a FROM t WHERE x = 1",
            "SELECT a FROM t WHERE x = 2",
            "postgresql");

        assertFalse(result.degraded(), "small change must use LCS, not degrade");
        assertEquals("LCS", result.method());
        assertFalse(result.ops().isEmpty(), "a value change must produce ops");
    }

    /** Identical SQL: no ops, still LCS / not degraded. */
    @Test
    void attributeChangeDetailed_identical_noOpsNotDegraded() throws Exception {
        DiffResult result = ChangeAttribution.attributeChangeDetailed(
            "SELECT a FROM t WHERE x = 1",
            "SELECT a FROM t WHERE x = 1",
            "postgresql");

        assertFalse(result.degraded());
        assertEquals("LCS", result.method());
        assertTrue(result.ops().isEmpty(), "identical SQL → no ops");
    }
}
