package com.mbworldwideapps.aiorchestration.modules.graph;

import java.util.List;

import com.mbworldwideapps.aiorchestration.modules.graph.GraphContextRetrieveResponse.GraphEdge;
import com.mbworldwideapps.aiorchestration.modules.graph.GraphContextRetrieveResponse.GraphNode;
import com.mbworldwideapps.aiorchestration.modules.graph.GraphContextRetrieveResponse.GraphPath;
import com.mbworldwideapps.aiorchestration.modules.graph.GraphContextRetrieveResponse.GraphWarning;

record GraphContextExpansion(
        boolean available,
        String unavailableReason,
        List<GraphPath> paths,
        List<GraphNode> nodes,
        List<GraphEdge> edges,
        List<GraphWarning> warnings) {

    GraphContextExpansion {
        paths = paths == null ? List.of() : List.copyOf(paths);
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        edges = edges == null ? List.of() : List.copyOf(edges);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    static GraphContextExpansion unavailable(String reason) {
        return new GraphContextExpansion(false, reason, List.of(), List.of(), List.of(),
                List.of(new GraphWarning("graph_unavailable", reason == null || reason.isBlank()
                        ? "Neo4j graph is unavailable" : reason)));
    }
}
