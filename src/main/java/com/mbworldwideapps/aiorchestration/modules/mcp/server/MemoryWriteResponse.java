package com.mbworldwideapps.aiorchestration.modules.mcp.server;

import java.util.List;
import java.util.UUID;

public record MemoryWriteResponse(
        UUID memoryId,
        String status,
        String sourceRef,
        String scope,
        String projectKey,
        String gateVerdict,
        Boolean confirmationRequired,
        String gateReason,
        String suggestedQuestion,
        List<String> contextHints) {

    public MemoryWriteResponse {
        contextHints = contextHints == null ? List.of() : List.copyOf(contextHints);
    }

    public MemoryWriteResponse(UUID memoryId, String status, String sourceRef) {
        this(memoryId, status, sourceRef, null, null);
    }

    public MemoryWriteResponse(UUID memoryId, String status, String sourceRef, String scope, String projectKey) {
        this(memoryId, status, sourceRef, scope, projectKey, null, null, null, null, List.of());
    }
}
