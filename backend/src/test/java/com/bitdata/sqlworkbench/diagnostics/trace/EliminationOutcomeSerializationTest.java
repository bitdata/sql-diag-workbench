package com.bitdata.sqlworkbench.diagnostics.trace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EliminationOutcomeSerializationTest {

    @Test void oldProgressWithoutEvidenceRemainsUnknown() throws Exception {
        var progress = mapper.readValue("{\"traceId\":\"old\",\"status\":\"COMPLETED\",\"steps\":[],\"findings\":[],\"totalElapsedMs\":1}", TraceProgress.class);
        assertNull(progress.evidence());
        assertNull(progress.eliminationOutcome());
    }

    private final ObjectMapper mapper = new ObjectMapper();

    private TraceStep step(int idx, String id, TraceStatus status) {
        return new TraceStep(idx, id, "Label-" + id, "", status, -1, 10, null);
    }

    @Test
    void edge_serializesWithOutcomeTypeEdge() throws Exception {
        EliminationOutcome.Edge edge = new EliminationOutcome.Edge(
            step(0, "child", TraceStatus.HAS_ROWS),
            step(1, "parent", TraceStatus.NO_ROWS));

        JsonNode json = mapper.valueToTree(edge);
        assertEquals("EDGE", json.get("outcomeType").asText());
        assertEquals("child", json.get("childStep").get("fragmentId").asText());
        assertEquals("parent", json.get("parentStep").get("fragmentId").asText());
    }

    @Test
    void error_serializesWithOutcomeTypeError() throws Exception {
        EliminationOutcome.Error error = new EliminationOutcome.Error(
            step(0, "leaf", TraceStatus.ERROR), "boom");

        JsonNode json = mapper.valueToTree(error);
        assertEquals("ERROR", json.get("outcomeType").asText());
        assertEquals("boom", json.get("reason").asText());
        assertEquals("leaf", json.get("step").get("fragmentId").asText());
    }

    @Test
    void missingAtSource_serializesWithOutcomeTypeMissingAtSource() throws Exception {
        EliminationOutcome.MissingAtSource m = new EliminationOutcome.MissingAtSource(
            step(0, "leaf", TraceStatus.NO_ROWS));

        JsonNode json = mapper.valueToTree(m);
        assertEquals("MISSING_AT_SOURCE", json.get("outcomeType").asText());
        assertEquals("leaf", json.get("leafStep").get("fragmentId").asText());
    }

    @Test
    void none_serializesWithOutcomeTypeNone() throws Exception {
        JsonNode json = mapper.valueToTree(new EliminationOutcome.None());
        assertEquals("NONE", json.get("outcomeType").asText());
    }

    // ── Round-trip / production nested path (PRD medium issue) ──────────
    //
    // The original suite only serialized (valueToTree) — it never read JSON
    // back. The rule fix in issue 03 makes the rule depend on reading
    // MissingAtSource.leafStep(); the frontend polls TraceProgress, so the
    // sealed outcome must survive a real serialize → deserialize round-trip
    // both standalone and nested inside the production envelope.

    @Test
    void roundTrip_missingAtSource_preservesSubtypeAndLeafStep() throws Exception {
        EliminationOutcome original = new EliminationOutcome.MissingAtSource(
            step(0, "leaf", TraceStatus.NO_ROWS));

        EliminationOutcome roundTrip = mapper.readValue(
            mapper.writeValueAsString(original), EliminationOutcome.class);

        assertInstanceOf(EliminationOutcome.MissingAtSource.class, roundTrip);
        assertEquals("MISSING_AT_SOURCE", roundTrip.outcomeType());
        assertEquals("leaf",
            ((EliminationOutcome.MissingAtSource) roundTrip).leafStep().fragmentId());
    }

    @Test
    void roundTrip_allVariants_preserveDiscriminatorAndSubtype() throws Exception {
        EliminationOutcome[] variants = {
            new EliminationOutcome.Edge(
                step(0, "c", TraceStatus.HAS_ROWS),
                step(1, "p", TraceStatus.NO_ROWS)),
            new EliminationOutcome.Error(step(0, "e", TraceStatus.ERROR), "boom"),
            new EliminationOutcome.MissingAtSource(step(0, "m", TraceStatus.NO_ROWS)),
            new EliminationOutcome.None(),
            new EliminationOutcome.Inconclusive("Current tree cannot establish a boundary")
        };
        String[] expected = {"EDGE", "ERROR", "MISSING_AT_SOURCE", "NONE", "INCONCLUSIVE"};

        for (int i = 0; i < variants.length; i++) {
            EliminationOutcome rt = mapper.readValue(
                mapper.writeValueAsString(variants[i]), EliminationOutcome.class);
            assertInstanceOf(variants[i].getClass(), rt,
                "variant " + expected[i] + " lost its subtype on round-trip");
            assertEquals(expected[i], rt.outcomeType(),
                "variant " + expected[i] + " lost its discriminator on round-trip");
        }
    }

    /** The production path: TraceProgress (polled by the frontend) carries the
     *  nested outcome. The discriminator must survive the envelope round-trip. */
    @Test
    void roundTrip_traceProgress_nestsMissingAtSourceOutcome() throws Exception {
        TraceProgress original = new TraceProgress(
            "trace-1", TraceStatus.COMPLETED, List.of(),
            step(0, "leaf", TraceStatus.NO_ROWS), List.of(), 42L, null,
            new EliminationOutcome.MissingAtSource(step(0, "leaf", TraceStatus.NO_ROWS)),
            null);

        TraceProgress roundTrip = mapper.readValue(
            mapper.writeValueAsString(original), TraceProgress.class);

        EliminationOutcome outcome = roundTrip.eliminationOutcome();
        assertInstanceOf(EliminationOutcome.MissingAtSource.class, outcome,
            "nested outcome subtype must survive the envelope round-trip");
        assertEquals("MISSING_AT_SOURCE", outcome.outcomeType());
        assertEquals("leaf",
            ((EliminationOutcome.MissingAtSource) outcome).leafStep().fragmentId());
    }
}
