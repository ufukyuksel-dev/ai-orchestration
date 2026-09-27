package com.mbworldwideapps.aiorchestration.modules.graph;

import java.util.List;

interface GraphContextNeo4jReader {

    GraphContextExpansion expand(String projectKey, List<GraphContextSeed> seeds, GraphContextExpansionOptions options);
}
