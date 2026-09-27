package com.mbworldwideapps.aiorchestration.modules.mcp.server;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record McpKeySummary(
        UUID id,
        String projectKey,
        String clientId,
        String keyPrefix,
        List<String> scopes,
        Instant createdAt,
        Instant lastUsedAt) {

    public static McpKeySummary from(McpApiKey key) {
        return new McpKeySummary(
                key.id(),
                key.projectKey(),
                key.clientId(),
                key.keyPrefix(),
                key.scopes(),
                key.createdAt(),
                key.lastUsedAt());
    }
}
