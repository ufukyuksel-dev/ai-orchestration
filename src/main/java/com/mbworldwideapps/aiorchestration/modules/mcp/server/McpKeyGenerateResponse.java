package com.mbworldwideapps.aiorchestration.modules.mcp.server;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record McpKeyGenerateResponse(
        UUID id,
        String projectKey,
        String clientId,
        String keyPrefix,
        String apiKey,
        List<String> scopes,
        Instant createdAt) {
}
