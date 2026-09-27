package com.mbworldwideapps.aiorchestration.modules.rules;

import java.util.List;

public record DetectorResult(Status status, List<DetectorFinding> findings) {

    public enum Status {
        MATCHED,
        CLEAN,
        /** Input could not be evaluated (size/timeout). Gate treats this as fail-closed, never as CLEAN. */
        INCONCLUSIVE
    }

    public static DetectorResult clean() {
        return new DetectorResult(Status.CLEAN, List.of());
    }

    public static DetectorResult inconclusive() {
        return new DetectorResult(Status.INCONCLUSIVE, List.of());
    }
}
