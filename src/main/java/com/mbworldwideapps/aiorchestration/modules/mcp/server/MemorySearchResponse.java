package com.mbworldwideapps.aiorchestration.modules.mcp.server;

import java.util.List;

import com.mbworldwideapps.aiorchestration.modules.memoryai.MemorySearchHit;

public record MemorySearchResponse(
        String projectKey,
        List<MemorySearchHit> items,
        long totalTokenEstimate,
        List<ScannedSymbol> scannedSymbols,
        int omittedScannedSymbols,
        List<com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryRelationService.ReferenceLink> linkedReferences,
        boolean moreLinkedReferences,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        String hint) {

    public MemorySearchResponse(String projectKey, List<MemorySearchHit> items, long totalTokenEstimate,
            List<ScannedSymbol> scannedSymbols, int omittedScannedSymbols,
            List<com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryRelationService.ReferenceLink> linkedReferences,
            boolean moreLinkedReferences) {
        this(projectKey, items, totalTokenEstimate, scannedSymbols, omittedScannedSymbols, linkedReferences,
                moreLinkedReferences, null);
    }

    public MemorySearchResponse {
        linkedReferences = linkedReferences == null ? List.of() : List.copyOf(linkedReferences);
        items = items == null ? List.of() : List.copyOf(items);
        scannedSymbols = scannedSymbols == null ? List.of() : List.copyOf(scannedSymbols);
    }
    /** Coordinates in a Scanner snapshot; not a live filesystem read. */
    public record ScannedSymbol(java.util.UUID symbolId, String fqn, String filePath,
            Integer startLine, Integer endLine, java.util.UUID scanRunId) {}
}
