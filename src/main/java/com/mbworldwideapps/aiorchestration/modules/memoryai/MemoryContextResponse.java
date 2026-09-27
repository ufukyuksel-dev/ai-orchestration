package com.mbworldwideapps.aiorchestration.modules.memoryai;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record MemoryContextResponse(
        List<MemoryContextItem> items,
        List<UUID> injectedMemoryIds,
        int injectedMemoryCount,
        int injectedTokenEstimate,
        Map<String, Integer> injectedScopes,
        int staleFlaggedCount,
        int conflictFlaggedCount,
        // Ranked (oversampled) candidates dropped by the injection relevance gate; not limited to
        // budget-eligible items, so it reads as "candidates filtered", not "injected memories dropped".
        int relevanceFilteredCount,
        long latencyMs) {

    /** Backwards-compatible constructor: records no relevance-filtered count. */
    public MemoryContextResponse(List<MemoryContextItem> items, List<UUID> injectedMemoryIds,
            int injectedMemoryCount, int injectedTokenEstimate, Map<String, Integer> injectedScopes,
            int staleFlaggedCount, int conflictFlaggedCount, long latencyMs) {
        this(items, injectedMemoryIds, injectedMemoryCount, injectedTokenEstimate, injectedScopes,
                staleFlaggedCount, conflictFlaggedCount, 0, latencyMs);
    }

    public static MemoryContextResponse empty() {
        return new MemoryContextResponse(List.of(), List.of(), 0, 0,
                Map.of("user", 0, "global", 0, "project", 0, "episodic", 0), 0, 0, 0L);
    }
}
