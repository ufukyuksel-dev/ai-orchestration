package com.mbworldwideapps.aiorchestration.modules.memoryai;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record MemoryItem(
        UUID id,
        UUID vectorId,
        MemoryScope scope,
        String projectKey,
        MemoryType memoryType,
        String summary,
        String text,
        List<String> tags,
        double confidence,
        MemoryStatus status,
        MemorySourceType sourceType,
        String sourceRef,
        String owner,
        Map<String, Object> metadata,
        Instant createdAt,
        Instant updatedAt,
        Instant lastUsedAt,
        Instant lastVerifiedAt,
        Instant expiresAt) {
}
