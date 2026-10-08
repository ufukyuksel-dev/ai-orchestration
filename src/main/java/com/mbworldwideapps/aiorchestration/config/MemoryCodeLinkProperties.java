package com.mbworldwideapps.aiorchestration.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How memories are linked to scanned code. After every completed scan the links are re-resolved; a memory is stale
 * while code it is bound to is missing and becomes active again once it resolves (or its locator is repaired).
 */
@ConfigurationProperties(prefix = "ai-orchestration.memory.code-links")
public record MemoryCodeLinkProperties(
        Boolean enabled,
        Boolean deterministicLinksEnabled,
        boolean similarityBackfillEnabled,
        double similarityMinScore,
        int similarityMaxLinksPerMemory,
        int batchSize,
        int queueCapacity) {

    public MemoryCodeLinkProperties {
        if (enabled == null) {
            enabled = true;
        }
        if (deterministicLinksEnabled == null) {
            deterministicLinksEnabled = true;
        }
        if (similarityMinScore <= 0.0 || similarityMinScore > 1.0) {
            similarityMinScore = 0.72;
        }
        if (similarityMaxLinksPerMemory <= 0) {
            similarityMaxLinksPerMemory = 5;
        }
        if (batchSize <= 0) {
            batchSize = 500;
        }
        if (queueCapacity <= 0) {
            queueCapacity = 20;
        }
    }

    public static MemoryCodeLinkProperties defaults() {
        return new MemoryCodeLinkProperties(true, true, false, 0.72, 5, 500, 20);
    }
}
