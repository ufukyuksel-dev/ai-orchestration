package com.mbworldwideapps.aiorchestration.modules.mcp.server;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record McpAccessLogEntry(
        UUID id,
        Instant timestamp,
        String projectKey,
        String clientId,
        String apiKeyPrefix,
        String toolName,
        String queryHash,
        Integer resultCount,
        int latencyMs,
        String decision,
        String errorClass,
        Map<String, Object> metadata) {

    public McpAccessLogEntry {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
