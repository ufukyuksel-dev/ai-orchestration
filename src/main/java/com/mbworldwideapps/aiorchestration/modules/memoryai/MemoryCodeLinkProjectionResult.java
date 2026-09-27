package com.mbworldwideapps.aiorchestration.modules.memoryai;

import java.util.UUID;

public record MemoryCodeLinkProjectionResult(
        UUID projectionRunId,
        String projectKey,
        UUID memoryId,
        boolean skipped,
        boolean available,
        int memoriesProcessed,
        int relationshipsMerged,
        int missingTargets,
        int staleRelationships,
        int unresolvedSourceRefs,
        int missingFiles,
        int missingSymbols,
        int unresolvedQdrantCandidates,
        int similarityCandidates,
        int similarityLinks,
        int similarityFailures,
        long durationMs,
        String degradedReason) {

    public MemoryCodeLinkProjectionResult {
        projectKey = projectKey == null ? "" : projectKey;
        degradedReason = degradedReason == null ? "" : degradedReason;
        memoriesProcessed = Math.max(0, memoriesProcessed);
        relationshipsMerged = Math.max(0, relationshipsMerged);
        missingTargets = Math.max(0, missingTargets);
        staleRelationships = Math.max(0, staleRelationships);
        unresolvedSourceRefs = Math.max(0, unresolvedSourceRefs);
        missingFiles = Math.max(0, missingFiles);
        missingSymbols = Math.max(0, missingSymbols);
        unresolvedQdrantCandidates = Math.max(0, unresolvedQdrantCandidates);
        similarityCandidates = Math.max(0, similarityCandidates);
        similarityLinks = Math.max(0, similarityLinks);
        similarityFailures = Math.max(0, similarityFailures);
        if (durationMs < 0) {
            durationMs = 0L;
        }
    }

    public static MemoryCodeLinkProjectionResult skipped(String projectKey, UUID memoryId, String reason) {
        return new MemoryCodeLinkProjectionResult(null, projectKey, memoryId, true, false, 0, 0, 0,
                0, 0, 0, 0, 0, 0, 0, 0, 0L, reason);
    }
}
