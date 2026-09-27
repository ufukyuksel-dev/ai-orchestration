package com.mbworldwideapps.aiorchestration.modules.memoryai;

import java.time.Instant;
import java.util.UUID;

record MemoryVectorProjectionWork(
        UUID memoryId,
        long revision,
        UUID previousVectorId,
        Instant requestedAt,
        Instant availableAt,
        int attemptCount,
        String lastError,
        Instant deadLetteredAt) {
}
