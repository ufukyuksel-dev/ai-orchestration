package com.mbworldwideapps.aiorchestration.modules.graph;

import java.util.List;
import java.util.Map;

public record GraphContextRetrieveResponse(
        String projectKey,
        String retrievalMode,
        List<GraphPath> paths,
        List<GraphNode> nodes,
        List<GraphEdge> edges,
        List<SourceRef> sourceRefs,
        List<String> memoryIds,
        List<String> symbolIds,
        List<String> capsuleKeys,
        List<GraphWarning> warnings,
        long tokenEstimate,
        Metadata metadata) {

    public record GraphPath(
            String pathId,
            List<String> nodeIds,
            List<String> edgeIds,
            double score,
            String seedKind,
            String seedKey) {
    }

    public record GraphNode(
            String id,
            String kind,
            String projectKey,
            String title,
            String summary,
            String text,
            String filePath,
            Integer startLine,
            Integer endLine,
            Double confidence,
            boolean stale,
            Map<String, Object> properties) {
    }

    public record GraphEdge(
            String id,
            String type,
            String sourceNodeId,
            String targetNodeId,
            String resolution,
            Double confidence,
            Double weight,
            boolean stale,
            Map<String, Object> properties) {
    }

    public record SourceRef(
            String kind,
            String id,
            String ref,
            Integer startLine,
            Integer endLine) {
    }

    public record GraphWarning(
            String code,
            String message) {
    }

    public record Metadata(
            int codeSeedCount,
            int memorySeedCount,
            int expandedNodeCount,
            int expandedEdgeCount,
            int expandedPathCount,
            boolean graphAvailable,
            boolean codePostgresFallbackUsed,
            boolean memoryPostgresFallbackUsed,
            long latencyMs,
            Map<String, Object> details) {

        public Metadata(int codeSeedCount, int memorySeedCount, int expandedNodeCount, int expandedEdgeCount,
                int expandedPathCount, boolean graphAvailable, boolean postgresFallbackUsed, long latencyMs,
                Map<String, Object> details) {
            this(codeSeedCount, memorySeedCount, expandedNodeCount, expandedEdgeCount, expandedPathCount,
                    graphAvailable, postgresFallbackUsed, postgresFallbackUsed, latencyMs, details);
        }

        public boolean postgresFallbackUsed() {
            return codePostgresFallbackUsed || memoryPostgresFallbackUsed;
        }
    }
}
