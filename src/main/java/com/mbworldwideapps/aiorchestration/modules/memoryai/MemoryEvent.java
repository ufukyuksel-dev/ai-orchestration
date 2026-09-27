package com.mbworldwideapps.aiorchestration.modules.memoryai;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record MemoryEvent(
        UUID id,
        UUID memoryId,
        MemoryEventType eventType,
        Map<String, Object> metadata,
        Instant createdAt) {
}
