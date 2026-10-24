package com.bitdata.sqlworkbench.execution.model;

/** Completeness of the materialized SELECT result, independent of returned row count. */
public record ResultCompleteness(State state, Integer appliedLimit, String errorReason) {
    public enum State { COMPLETE, TRUNCATED, UNKNOWN }

    public ResultCompleteness {
        if (state == null || (state != State.UNKNOWN && (errorReason != null ||
                (appliedLimit == null || appliedLimit < 1 || appliedLimit > 10_000)))) {
            state = State.UNKNOWN;
            appliedLimit = null;
            errorReason = "COMPLETENESS_UNVERIFIED";
        }
        if (state == State.UNKNOWN && (errorReason == null || errorReason.isBlank()))
            errorReason = "COMPLETENESS_UNVERIFIED";
    }

    public static ResultCompleteness unknown() {
        return new ResultCompleteness(State.UNKNOWN, null, "COMPLETENESS_UNVERIFIED");
    }

    public static ResultCompleteness normalize(ResultCompleteness value) {
        return value == null ? unknown() : value;
    }
}
