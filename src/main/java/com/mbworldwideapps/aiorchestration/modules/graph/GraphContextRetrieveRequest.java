package com.mbworldwideapps.aiorchestration.modules.graph;

public record GraphContextRetrieveRequest(
        String query,
        String projectKey,
        Integer topK,
        Integer maxDepth,
        Boolean includeRawSourceLines,
        String retrievalMode,
        Boolean includeStale,
        Boolean includeSimilarityBackfill,
        Boolean includeMemorySeeds,
        Boolean includeMemoryTraversal,
        boolean includeSharedReferences) {

    public GraphContextRetrieveRequest(String query, String projectKey, Integer topK, Integer maxDepth,
            Boolean includeRawSourceLines, String retrievalMode, Boolean includeStale, Boolean includeSimilarityBackfill,
            Boolean includeMemorySeeds, Boolean includeMemoryTraversal) {
        this(query, projectKey, topK, maxDepth, includeRawSourceLines, retrievalMode, includeStale,
                includeSimilarityBackfill, includeMemorySeeds, includeMemoryTraversal, false);
    }

    public GraphContextRetrieveRequest(String query, String projectKey, Integer topK, Integer maxDepth,
            Boolean includeRawSourceLines, String retrievalMode, Boolean includeStale,
            Boolean includeSimilarityBackfill) {
        this(query, projectKey, topK, maxDepth, includeRawSourceLines, retrievalMode, includeStale,
                includeSimilarityBackfill, null, null);
    }

    public GraphContextRetrieveRequest(String query, String projectKey, Integer topK, Integer maxDepth,
            Boolean includeRawSourceLines, String retrievalMode, Boolean includeStale,
            Boolean includeSimilarityBackfill, Boolean includeMemorySeeds) {
        this(query, projectKey, topK, maxDepth, includeRawSourceLines, retrievalMode, includeStale,
                includeSimilarityBackfill, includeMemorySeeds, null);
    }
}
