package com.mbworldwideapps.aiorchestration.modules.context;

public record LearningContextRequest(
        String query,
        String projectKey,
        String userId,
        String role,
        Integer topK,
        Integer maxDepth,
        Integer tokenBudget,
        Boolean includeMemoryFallback,
        Boolean includeSemanticAnchors,
        String retrievalMode,
        Boolean includeStale,
        Boolean includeSimilarityBackfill,
        String contextMode) {

    public LearningContextRequest(String query, String projectKey, String userId, String role, Integer topK,
            Integer maxDepth, Integer tokenBudget, Boolean includeMemoryFallback, Boolean includeSemanticAnchors,
            String retrievalMode, Boolean includeStale, Boolean includeSimilarityBackfill) {
        this(query, projectKey, userId, role, topK, maxDepth, tokenBudget, includeMemoryFallback,
                includeSemanticAnchors, retrievalMode, includeStale, includeSimilarityBackfill, null);
    }
}
