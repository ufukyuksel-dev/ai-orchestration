package com.mbworldwideapps.aiorchestration.modules.mcp.server;

import java.util.UUID;

public record MemoryUpdateResponse(
        UUID memoryId,
        String status,
        String scope,
        String projectKey,
        String sourceRef,
        UUID vectorId,
        String actor) {
}
