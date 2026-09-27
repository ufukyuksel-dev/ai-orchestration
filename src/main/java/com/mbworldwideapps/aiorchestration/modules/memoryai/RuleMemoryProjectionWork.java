package com.mbworldwideapps.aiorchestration.modules.memoryai;

import java.time.Instant;
import java.util.UUID;

/** Durable, retryable projection request written with a rule promotion. */
record RuleMemoryProjectionWork(
        UUID memoryId,
        UUID ruleId,
        int ruleVersion,
        Instant requestedAt,
        Instant availableAt,
        Instant vectorProjectedAt,
        Instant graphMarkedAt,
        Instant semanticMarkedAt,
        int attemptCount,
        String lastError) {

    boolean vectorProjected() {
        return vectorProjectedAt != null;
    }

    boolean graphMarked() {
        return graphMarkedAt != null;
    }

    boolean semanticMarked() {
        return semanticMarkedAt != null;
    }
}
