package com.mbworldwideapps.aiorchestration.modules.memoryai;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record ReviewQueueItem(
        UUID id,
        UUID candidateMemoryId,
        ReviewReason reason,
        ReviewStatus status,
        Map<String, Object> metadata,
        Instant createdAt,
        Instant reviewedAt) {
}
