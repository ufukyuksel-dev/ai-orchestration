package com.mbworldwideapps.aiorchestration.modules.llmgateway;

import java.util.List;

import com.mbworldwideapps.aiorchestration.modules.llmgateway.SourceSnippet;

public record GenerationRequest(
        String question,
        String userId,
        List<SourceSnippet> sources,
        String freshness,
        String requestedProvider,
        String memoryContextBlock,
        int injectedMemoryCount,
        String role) {

    public GenerationRequest {
        if (sources == null) {
            sources = List.of();
        }
        if (injectedMemoryCount < 0) {
            injectedMemoryCount = 0;
        }
        if (role == null || role.isBlank()) {
            role = "unknown";
        }
    }
}
