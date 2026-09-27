package com.mbworldwideapps.aiorchestration.modules.memoryai;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record MemorySearchHit(
        UUID memoryId,
        UUID vectorId,
        String citationId,
        String scope,
        String projectKey,
        String memoryType,
        String summary,
        String excerpt,
        List<String> tags,
        double confidence,
        boolean stale,
        int tokenEstimate,
        double semanticScore,
        Instant lastVerifiedAt,
        String status,
        List<MemoryCodeLocator> declaredCodeLocators,
        int omittedCodeLocators,
        List<String> warnings) {

    public MemorySearchHit {
        tags = tags == null ? List.of() : List.copyOf(tags);
        declaredCodeLocators = declaredCodeLocators == null ? List.of() : List.copyOf(declaredCodeLocators);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
