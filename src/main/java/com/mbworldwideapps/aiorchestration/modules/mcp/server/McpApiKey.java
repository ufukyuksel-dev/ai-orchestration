package com.mbworldwideapps.aiorchestration.modules.mcp.server;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record McpApiKey(
        UUID id,
        String projectKey,
        String clientId,
        String apiKeyHash,
        String keyPrefix,
        List<String> scopes,
        Instant createdAt,
        Instant revokedAt,
        Instant lastUsedAt) {

    public McpApiKey {
        scopes = scopes == null ? List.of() : List.copyOf(scopes);
    }
}
