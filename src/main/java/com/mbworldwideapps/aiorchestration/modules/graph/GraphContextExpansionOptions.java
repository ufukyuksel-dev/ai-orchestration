package com.mbworldwideapps.aiorchestration.modules.graph;

record GraphContextExpansionOptions(
        int maxDepth,
        int maxNodes,
        int maxEdges,
        int maxPaths,
        int neighborsPerNode,
        int frontierPerHop,
        boolean includeStale,
        boolean includeSimilarityBackfill,
        boolean includeSharedReferences) {
    GraphContextExpansionOptions(int maxDepth, int maxNodes, int maxEdges, int maxPaths, int neighborsPerNode,
            int frontierPerHop, boolean includeStale, boolean includeSimilarityBackfill) {
        this(maxDepth, maxNodes, maxEdges, maxPaths, neighborsPerNode, frontierPerHop, includeStale, includeSimilarityBackfill, false);
    }
}
