package com.mbworldwideapps.aiorchestration.modules.memoryai;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

@ConfigurationProperties(prefix = "ai-orchestration.memory.ranking")
public record MemoryRankingProperties(
        Double semanticWeight,
        Double confidenceWeight,
        Double stalePenalty,
        Double scopeBoost,
        Integer oversampleFactor,
        Double minInjectionSimilarity,
        Double minSearchSimilarity) {

    /** Backwards-compatible constructor retained for callers compiled against the original ranking shape. */
    public MemoryRankingProperties(Double semanticWeight, Double confidenceWeight, Double stalePenalty,
            Double scopeBoost, Integer oversampleFactor, Double minInjectionSimilarity) {
        this(semanticWeight, confidenceWeight, stalePenalty, scopeBoost, oversampleFactor,
                minInjectionSimilarity, null);
    }

    @ConstructorBinding
    public MemoryRankingProperties {
        if (semanticWeight == null) {
            semanticWeight = 0.75;
        }
        if (confidenceWeight == null) {
            confidenceWeight = 0.20;
        }
        if (stalePenalty == null) {
            stalePenalty = 0.10;
        }
        if (scopeBoost == null) {
            scopeBoost = 0.05;
        }
        if (oversampleFactor == null || oversampleFactor < 1) {
            oversampleFactor = 2;
        }
        if (minInjectionSimilarity == null) {
            // Local embeddings can assign useful project memories scores near 0.12.
            // A light floor removes near-zero noise without silently disabling those hits.
            minInjectionSimilarity = 0.10;
        }
        if (minSearchSimilarity == null) {
            minSearchSimilarity = 0.10;
        }
    }

    public static MemoryRankingProperties defaults() {
        return new MemoryRankingProperties(null, null, null, null, null, null, null);
    }

    public double scoreFor(double semanticScore, double confidence, boolean stale, MemoryScope scope) {
        double base = semanticWeight * semanticScore
                + confidenceWeight * confidence
                - stalePenalty * (stale ? 1.0 : 0.0);
        if (scope == MemoryScope.PROJECT || scope == MemoryScope.USER) {
            base += scopeBoost;
        }
        return base;
    }
}
