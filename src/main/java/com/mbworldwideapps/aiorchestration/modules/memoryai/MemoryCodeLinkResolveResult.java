package com.mbworldwideapps.aiorchestration.modules.memoryai;

import java.util.List;

public record MemoryCodeLinkResolveResult(
        List<MemoryCodeLinkRecord> links,
        int unresolvedSourceRefs,
        int missingFiles,
        int missingSymbols,
        int unresolvedQdrantCandidates,
        int similarityCandidates,
        int similarityFailures,
        int missingTypedTargets) {

    public MemoryCodeLinkResolveResult {
        links = links == null ? List.of() : List.copyOf(links);
        unresolvedSourceRefs = Math.max(0, unresolvedSourceRefs);
        missingFiles = Math.max(0, missingFiles);
        missingSymbols = Math.max(0, missingSymbols);
        unresolvedQdrantCandidates = Math.max(0, unresolvedQdrantCandidates);
        similarityCandidates = Math.max(0, similarityCandidates);
        similarityFailures = Math.max(0, similarityFailures);
        missingTypedTargets = Math.max(0, missingTypedTargets);
    }

    public static MemoryCodeLinkResolveResult empty() {
        return new MemoryCodeLinkResolveResult(List.of(), 0, 0, 0, 0, 0, 0, 0);
    }
}
