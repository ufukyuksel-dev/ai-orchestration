package com.mbworldwideapps.aiorchestration.modules.llmgateway;

import java.time.Instant;

public record SourceSnippet(
        String chunkId,
        String title,
        String sourceUrl,
        String snippet,
        double score,
        String freshness,
        Instant lastSyncedAt) {
}
