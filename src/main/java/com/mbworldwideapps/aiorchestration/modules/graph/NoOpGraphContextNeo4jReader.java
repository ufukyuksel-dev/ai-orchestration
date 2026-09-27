package com.mbworldwideapps.aiorchestration.modules.graph;

import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "ai-orchestration.graph.neo4j", name = "enabled",
        havingValue = "false", matchIfMissing = true)
class NoOpGraphContextNeo4jReader implements GraphContextNeo4jReader {

    @Override
    public GraphContextExpansion expand(String projectKey, List<GraphContextSeed> seeds,
            GraphContextExpansionOptions options) {
        return GraphContextExpansion.unavailable("Neo4j graph is disabled");
    }
}
