package com.mbworldwideapps.aiorchestration.modules.memoryai;

import java.time.Instant;
import java.util.UUID;

record MemoryVectorProjectionState(
        UUID memoryId,
        long appliedRevision,
        UUID vectorId,
        String retrievalTextHash,
        String projectionHash,
        String embeddingFingerprint,
        Operation appliedOperation,
        Instant appliedAt) {

    enum Operation {
        UPSERT,
        DELETE
    }
}
