package com.mbworldwideapps.aiorchestration.modules.context;

import java.util.List;
import java.util.Map;

import com.mbworldwideapps.aiorchestration.modules.graph.GraphContextRetrieveResponse;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryContextResponse;

public record LearningContextResponse(
        String projectKey,
        List<LearningContextItem> items,
        MemoryContextResponse memoryContext,
        GraphContextRetrieveResponse graphContext,
        int tokenEstimate,
        boolean graphAvailable,
        boolean graphSeedFallbackUsed,
        boolean graphCodeSeedFallbackUsed,
        boolean graphMemorySeedFallbackUsed,
        boolean fallbackUsed,
        List<String> warnings,
        Map<String, Object> metadata) {

    public LearningContextResponse(String projectKey, List<LearningContextItem> items,
            MemoryContextResponse memoryContext, GraphContextRetrieveResponse graphContext,
            int tokenEstimate, boolean graphAvailable,
            boolean graphSeedFallbackUsed, boolean fallbackUsed,
            List<String> warnings, Map<String, Object> metadata) {
        this(projectKey, items, memoryContext, graphContext, tokenEstimate, graphAvailable,
                graphSeedFallbackUsed, graphSeedFallbackUsed, graphSeedFallbackUsed,
                fallbackUsed, warnings, metadata);
    }

    public LearningContextResponse(String projectKey, List<LearningContextItem> items,
            MemoryContextResponse memoryContext, GraphContextRetrieveResponse graphContext,
            int tokenEstimate, boolean graphAvailable,
            boolean fallbackUsed, List<String> warnings, Map<String, Object> metadata) {
        this(projectKey, items, memoryContext, graphContext, tokenEstimate, graphAvailable,
                false, false, false, fallbackUsed, warnings, metadata);
    }

    public LearningContextResponse {
        projectKey = projectKey == null ? "" : projectKey.trim();
        items = items == null ? List.of() : List.copyOf(items);
        memoryContext = memoryContext == null ? MemoryContextResponse.empty() : memoryContext;
        tokenEstimate = Math.max(0, tokenEstimate);
        graphSeedFallbackUsed = graphSeedFallbackUsed || graphCodeSeedFallbackUsed || graphMemorySeedFallbackUsed;
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
