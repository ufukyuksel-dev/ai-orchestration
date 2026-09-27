package com.mbworldwideapps.aiorchestration.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

@ConfigurationProperties(prefix = "ai-orchestration.learning-context")
public record LearningContextProperties(
        boolean enabled,
        int defaultTopK,
        int maxTopK,
        int defaultMaxDepth,
        int maxDepth,
        int defaultTokenBudget,
        int maxTokenBudget,
        int catastrophicItemMultiplier,
        int maxRenderedItemChars,
        boolean includeMemoryFallback,
        boolean includeSemanticAnchors,
        boolean includeRawSourceLines,
        boolean includeStale,
        boolean includeSimilarityBackfill) {

    @ConstructorBinding
    public LearningContextProperties {
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
        if (defaultTokenBudget <= 0) {
            defaultTokenBudget = 1200;
        }
        if (maxTokenBudget <= 0 || maxTokenBudget < defaultTokenBudget) {
            maxTokenBudget = 4000;
        }
        if (catastrophicItemMultiplier <= 0) {
            catastrophicItemMultiplier = 2;
        }
        if (maxRenderedItemChars <= 0) {
            maxRenderedItemChars = 800;
        }
    }
}
