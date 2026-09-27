package com.mbworldwideapps.aiorchestration.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

@ConfigurationProperties(prefix = "ai-orchestration.graph.context-retrieval")
public record GraphContextRetrievalProperties(
        boolean enabled,
        int defaultTopK,
        int maxTopK,
        int defaultMaxDepth,
        int maxDepth,
        int seedLimit,
        int maxNodes,
        int maxEdges,
        int maxPaths,
        int neighborsPerNode,
        int frontierPerHop,
        int maxCapsuleTextChars,
        int maxMemorySummaryChars,
        int tokenBudget,
        boolean includeSimilarityBackfillByDefault,
        boolean includeStaleByDefault) {

    @ConstructorBinding
    public GraphContextRetrievalProperties {
        if (defaultTopK <= 0) {
            defaultTopK = 5;
        }
        if (maxTopK <= 0 || maxTopK < defaultTopK) {
            maxTopK = 20;
        }
        if (defaultMaxDepth <= 0) {
            defaultMaxDepth = 2;
        }
        if (maxDepth <= 0) {
            maxDepth = 3;
        }
        if (maxDepth > 3) {
            maxDepth = 3;
        }
        if (defaultMaxDepth > maxDepth) {
            defaultMaxDepth = maxDepth;
        }
        if (seedLimit <= 0) {
            seedLimit = 8;
        }
        if (maxNodes <= 0) {
            maxNodes = 80;
        }
        if (maxEdges <= 0) {
            maxEdges = 160;
        }
        if (maxPaths <= 0) {
            maxPaths = 12;
        }
        if (neighborsPerNode <= 0) {
            neighborsPerNode = 12;
        }
        if (frontierPerHop <= 0) {
            frontierPerHop = 32;
        }
        if (maxCapsuleTextChars <= 0) {
            maxCapsuleTextChars = 800;
        }
        if (maxMemorySummaryChars <= 0) {
            maxMemorySummaryChars = 500;
        }
        if (tokenBudget <= 0) {
            tokenBudget = 4000;
        }
    }
}
