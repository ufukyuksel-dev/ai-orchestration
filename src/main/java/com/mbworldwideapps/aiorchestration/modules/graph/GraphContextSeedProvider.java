package com.mbworldwideapps.aiorchestration.modules.graph;

import java.util.List;

interface GraphContextSeedProvider {

    GraphContextSeedResult seeds(String query, String projectKey, int limit);

    default GraphContextSeedResult seeds(String query, String projectKey, int limit, boolean includeMemory) {
        GraphContextSeedResult result = seeds(query, projectKey, limit);
        if (includeMemory || result == null) {
            return result;
        }
        List<GraphContextSeed> codeSeeds = result.seeds().stream()
                .filter(GraphContextSeed::isCode)
                .toList();
        return new GraphContextSeedResult(codeSeeds, result.warnings(), result.codePostgresFallbackUsed(), false);
    }

    default GraphContextSeedResult seeds(String query, String projectKey, int limit, boolean includeMemory,
            boolean hybridFusionEnabled) {
        return seeds(query, projectKey, limit, includeMemory);
    }
}
