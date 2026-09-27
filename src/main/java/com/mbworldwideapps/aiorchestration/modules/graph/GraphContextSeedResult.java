package com.mbworldwideapps.aiorchestration.modules.graph;

import java.util.List;

import com.mbworldwideapps.aiorchestration.modules.graph.GraphContextRetrieveResponse.GraphWarning;

record GraphContextSeedResult(
        List<GraphContextSeed> seeds,
        List<GraphWarning> warnings,
        boolean codePostgresFallbackUsed,
        boolean memoryPostgresFallbackUsed) {

    GraphContextSeedResult(List<GraphContextSeed> seeds, List<GraphWarning> warnings,
            boolean postgresFallbackUsed) {
        this(seeds, warnings, postgresFallbackUsed, postgresFallbackUsed);
    }

    GraphContextSeedResult {
        seeds = seeds == null ? List.of() : List.copyOf(seeds);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    boolean postgresFallbackUsed() {
        return codePostgresFallbackUsed || memoryPostgresFallbackUsed;
    }

    int codeSeedCount() {
        return (int) seeds.stream().filter(GraphContextSeed::isCode).count();
    }

    int memorySeedCount() {
        return (int) seeds.stream().filter(GraphContextSeed::isMemory).count();
    }
}
