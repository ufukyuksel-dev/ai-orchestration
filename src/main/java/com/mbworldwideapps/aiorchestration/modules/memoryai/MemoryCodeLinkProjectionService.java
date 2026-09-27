package com.mbworldwideapps.aiorchestration.modules.memoryai;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.mbworldwideapps.aiorchestration.config.MemoryCodeLinkProperties;
import org.springframework.stereotype.Service;

@Service
public class MemoryCodeLinkProjectionService {

    private final MemoryCodeLinkProperties properties;
    private final MemoryRepository memoryRepository;
    private final MemoryCodeLinkResolver resolver;
    private final MemoryLifecycleService lifecycle;

    public MemoryCodeLinkProjectionService(MemoryCodeLinkProperties properties, MemoryRepository memoryRepository,
            MemoryCodeLinkResolver resolver, MemoryLifecycleService lifecycle) {
        this.properties = properties;
        this.memoryRepository = memoryRepository;
        this.resolver = resolver;
        this.lifecycle = lifecycle;
    }

    public MemoryCodeLinkProjectionResult linkMemory(UUID memoryId) {
        if (!enabled()) {
            return MemoryCodeLinkProjectionResult.skipped("", memoryId, "disabled");
        }
        if (memoryId == null) {
            throw new IllegalArgumentException("memoryId is required");
        }
        Instant started = Instant.now();
        UUID projectionRunId = UUID.randomUUID();
        MemoryItem item = memoryRepository.findById(memoryId).orElse(null);
        if (item == null) {
            return MemoryCodeLinkProjectionResult.skipped("", memoryId, "memory_not_found");
        }
        if (!eligible(item.status())) {
            return result(projectionRunId, item.projectKey(), memoryId, 1, 0, 0, 0,
                    MemoryCodeLinkResolveResult.empty(), 0, true, "", started);
        }
        try {
            MemoryCodeLinkResolveResult resolved = resolver.resolve(item);
            if (properties.deterministicLinksEnabled()) lifecycle.observe(item, resolved);
            return result(projectionRunId, item.projectKey(), memoryId, 1, resolved.links().size(),
                    0, 0, resolved, similarityLinks(resolved.links()), true, "", started);
        } catch (RuntimeException e) {
            return degraded(projectionRunId, item.projectKey(), memoryId, 1, started, e);
        }
    }

    public MemoryCodeLinkProjectionResult linkAll(String projectKey) {
        if (!enabled()) {
            return MemoryCodeLinkProjectionResult.skipped(projectKey, null, "disabled");
        }
        Instant started = Instant.now();
        UUID projectionRunId = UUID.randomUUID();
        String effectiveProjectKey = projectKey == null ? "" : projectKey.trim();
        Counts counts = new Counts();
        UUID afterId = null;
        int batchSize = properties.batchSize();
        try {
            while (true) {
                List<MemoryItem> batch = memoryRepository.findEligibleForGraphProjection(effectiveProjectKey,
                        afterId, batchSize);
                if (batch.isEmpty()) {
                    break;
                }
                for (MemoryItem item : batch) {
                    MemoryCodeLinkResolveResult resolved = resolver.resolve(item);
                    counts.addResolved(resolved);
                    counts.relationshipsMerged += resolved.links().size();
                    counts.similarityLinks += similarityLinks(resolved.links());
                    if (properties.deterministicLinksEnabled()) lifecycle.observe(item, resolved);
                    counts.memoriesProcessed++;
                }
                afterId = batch.get(batch.size() - 1).id();
            }
            return result(projectionRunId, effectiveProjectKey, null, counts.memoriesProcessed,
                    counts.relationshipsMerged, counts.missingTargets, counts.staleRelationships,
                    counts.toResolveResult(), counts.similarityLinks, true, "", started);
        } catch (RuntimeException e) {
            return new MemoryCodeLinkProjectionResult(projectionRunId, effectiveProjectKey, null, false, false,
                    counts.memoriesProcessed, counts.relationshipsMerged, counts.missingTargets,
                    counts.staleRelationships, counts.unresolvedSourceRefs, counts.missingFiles,
                    counts.missingSymbols, counts.unresolvedQdrantCandidates, counts.similarityCandidates,
                    counts.similarityLinks, counts.similarityFailures, elapsedMs(started),
                    e.getClass().getSimpleName());
        }
    }

    private boolean enabled() {
        return properties.enabled();
    }

    private static boolean eligible(MemoryStatus status) {
        return status == MemoryStatus.ACTIVE || status == MemoryStatus.STALE;
    }

    private static MemoryCodeLinkProjectionResult degraded(UUID projectionRunId, String projectKey, UUID memoryId,
            int memoriesProcessed, Instant started, RuntimeException e) {
        return new MemoryCodeLinkProjectionResult(projectionRunId, projectKey, memoryId, false, false,
                memoriesProcessed, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, elapsedMs(started),
                e.getClass().getSimpleName());
    }

    private static MemoryCodeLinkProjectionResult result(UUID projectionRunId, String projectKey, UUID memoryId,
            int memoriesProcessed, int relationshipsMerged, int missingTargets, int staleRelationships,
            MemoryCodeLinkResolveResult resolved, int similarityLinks, boolean available, String degradedReason,
            Instant started) {
        return new MemoryCodeLinkProjectionResult(projectionRunId, projectKey, memoryId, false, available,
                memoriesProcessed, relationshipsMerged, missingTargets, staleRelationships,
                resolved.unresolvedSourceRefs(), resolved.missingFiles(), resolved.missingSymbols(),
                resolved.unresolvedQdrantCandidates(), resolved.similarityCandidates(), similarityLinks,
                resolved.similarityFailures(), elapsedMs(started), degradedReason);
    }

    private static int similarityLinks(List<MemoryCodeLinkRecord> links) {
        return (int) links.stream()
                .filter(link -> link.relationshipType() == MemoryCodeRelationshipType.RELATED_TO)
                .count();
    }

    private static long elapsedMs(Instant started) {
        return Duration.between(started, Instant.now()).toMillis();
    }

    private static final class Counts {
        private int memoriesProcessed;
        private int relationshipsMerged;
        private int missingTargets;
        private int staleRelationships;
        private int unresolvedSourceRefs;
        private int missingFiles;
        private int missingSymbols;
        private int missingTypedTargets;
        private int unresolvedQdrantCandidates;
        private int similarityCandidates;
        private int similarityLinks;
        private int similarityFailures;

        void addResolved(MemoryCodeLinkResolveResult resolved) {
            unresolvedSourceRefs += resolved.unresolvedSourceRefs();
            missingFiles += resolved.missingFiles();
            missingSymbols += resolved.missingSymbols();
            missingTypedTargets += resolved.missingTypedTargets();
            unresolvedQdrantCandidates += resolved.unresolvedQdrantCandidates();
            similarityCandidates += resolved.similarityCandidates();
            similarityFailures += resolved.similarityFailures();
        }

        MemoryCodeLinkResolveResult toResolveResult() {
            return new MemoryCodeLinkResolveResult(List.of(), unresolvedSourceRefs, missingFiles, missingSymbols,
                    unresolvedQdrantCandidates, similarityCandidates, similarityFailures, missingTypedTargets);
        }
    }
}
