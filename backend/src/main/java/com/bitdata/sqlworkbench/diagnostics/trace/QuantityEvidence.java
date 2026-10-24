package com.bitdata.sqlworkbench.diagnostics.trace;

import java.math.BigInteger;
import java.util.List;

public record QuantityEvidence(String expectedMax, String rootCount, String verdict, List<Comparison> comparisons) {
    public record Observation(String fragmentId, Integer ordinal, boolean reusedObservation) {}
    public record Comparison(String kind, String parentFragmentId, List<Observation> observations, List<TraceLimitation> limitations) {}
    public static QuantityEvidence summarize(TraceRequest request, String rootId, TraceStatus status, List<TraceStep> steps, List<Comparison> comparisons) {
        String count=steps.stream().filter(s -> java.util.Objects.equals(rootId,s.fragmentId()) && s.count()!=null)
            .map(TraceStep::count).findFirst().orElse(null);
        String verdict="UNKNOWN";
        if(status==TraceStatus.COMPLETED && count!=null) verdict=request.expectedMax()==null ? "OBSERVATION_ONLY" :
            new BigInteger(count).compareTo(new BigInteger(request.expectedMax()))>0 ? "EXCEEDS_EXPECTATION" : "WITHIN_EXPECTATION";
        return new QuantityEvidence(request.expectedMax(),count,verdict,List.copyOf(comparisons));
    }
}
