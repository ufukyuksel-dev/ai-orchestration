package com.mbworldwideapps.aiorchestration.modules.rules;

import java.time.Instant;
import java.util.UUID;

public record RuleDefinition(
        UUID id,
        UUID originMemoryId,
        String projectKey,
        int currentVersion,
        RuleStatus status,
        Instant createdAt,
        Instant updatedAt) {
}
