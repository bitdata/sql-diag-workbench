package com.bitdata.sqlworkbench.diagnostics.trace;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Result of elimination-point detection. The detector stays pure — the
 * {@code Edge} variant carries only the two steps; diff enrichment
 * ({@link EliminationEdge}) happens in {@link RowTraceEngine}.
 *
 * <p>Priority: root present → None; observed Edge → Error → Inconclusive.
 *
 * <p>Jackson polymorphic config: each variant serializes with a discriminator
 * property {@code outcomeType} so the frontend can switch on it without
 * probing nullable fields. See {@code EliminationOutcomeSerializationTest}.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "outcomeType")
@JsonSubTypes({
    @JsonSubTypes.Type(value = EliminationOutcome.Edge.class, name = "EDGE"),
    @JsonSubTypes.Type(value = EliminationOutcome.Error.class, name = "ERROR"),
    @JsonSubTypes.Type(value = EliminationOutcome.MissingAtSource.class, name = "MISSING_AT_SOURCE"),
    @JsonSubTypes.Type(value = EliminationOutcome.None.class, name = "NONE"),
    @JsonSubTypes.Type(value = EliminationOutcome.Inconclusive.class, name = "INCONCLUSIVE")
})
public sealed interface EliminationOutcome {

    /**
     * Discriminator value matching the Jackson {@code @JsonSubTypes} names.
     * Returns {@code "EDGE"} / {@code "ERROR"} / {@code "MISSING_AT_SOURCE"} /
     * {@code "NONE"} / {@code "INCONCLUSIVE"}. Derived from the runtime class
     * so the Java accessor stays in sync with the JSON shape.
     */
    default String outcomeType() {
        String name = getClass().getSimpleName();
        return switch (name) {
            case "Edge" -> "EDGE";
            case "Error" -> "ERROR";
            case "MissingAtSource" -> "MISSING_AT_SOURCE";
            case "None" -> "NONE";
            case "Inconclusive" -> "INCONCLUSIVE";
            default -> name;
        };
    }

    /** A child fragment HAS_ROWS while its parent NO_ROWS — the elimination edge. */
    record Edge(TraceStep childStep, TraceStep parentStep) implements EliminationOutcome {}

    /** A step errored/timed out, so no elimination edge can be determined. */
    record Error(TraceStep step, String reason) implements EliminationOutcome {}

    /** Historical payload only. Current-tree absence does not prove source absence. */
    record MissingAtSource(TraceStep leafStep) implements EliminationOutcome {}

    /** The submitted root query successfully found the key. */
    record None() implements EliminationOutcome {}

    record Inconclusive(String reason) implements EliminationOutcome {}
}
