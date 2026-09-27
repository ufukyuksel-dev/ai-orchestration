package com.mbworldwideapps.aiorchestration.modules.llmgateway;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.mbworldwideapps.aiorchestration.modules.llmgateway.SourceSnippet;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryContextItem;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryContextResponse;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryScope;
import org.springframework.stereotype.Component;

@Component
public class ContextInjectionBudgetGate {

    private static final long CATASTROPHIC_MULTIPLIER = 2L;

    public record BudgetConfig(int memoryCap, int knowledgeCap, int totalCap) {

        public BudgetConfig {
            if (memoryCap <= 0) {
                memoryCap = 1500;
            }
            if (knowledgeCap <= 0) {
                knowledgeCap = 3000;
            }
            if (totalCap <= 0) {
                totalCap = 4500;
            }
        }

        public static BudgetConfig defaults() {
            return new BudgetConfig(1500, 3000, 4500);
        }
    }

    public record TrimResult(
            List<SourceSnippet> trimmedSources,
            MemoryContextResponse trimmedMemoryContext,
            long memoryTokens,
            long knowledgeTokens,
            long totalTokens,
            boolean contextTrimmed,
            List<UUID> trimmedMemoryIds,
            List<String> trimmedKnowledgeChunkIds) {
    }

    public TrimResult trim(List<SourceSnippet> sources, MemoryContextResponse memoryContext) {
        return trim(sources, memoryContext, BudgetConfig.defaults());
    }

    public TrimResult trim(List<SourceSnippet> sources, MemoryContextResponse memoryContext, BudgetConfig config) {
        List<SourceSnippet> safeSources = sources == null ? List.of() : List.copyOf(sources);
        MemoryContextResponse safeMemory = memoryContext == null ? MemoryContextResponse.empty() : memoryContext;

        rejectCatastrophicItems(safeSources, safeMemory, config);

        List<MemoryContextItem> keptMemory = new ArrayList<>();
        Set<UUID> keptMemoryIds = new LinkedHashSet<>();
        long memoryTokens = 0L;
        for (MemoryContextItem item : safeMemory.items().stream()
                .sorted(Comparator.comparingDouble(MemoryContextItem::confidence).reversed())
                .toList()) {
            long itemTokens = memoryTokens(item);
            if (memoryTokens + itemTokens <= config.memoryCap()) {
                keptMemory.add(item);
                keptMemoryIds.add(item.memoryId());
                memoryTokens += itemTokens;
            }
        }

        List<SourceSnippet> keptSources = new ArrayList<>();
        Set<String> keptChunkIds = new LinkedHashSet<>();
        long knowledgeTokens = 0L;
        for (SourceSnippet source : safeSources) {
            long sourceTokens = knowledgeTokens(source);
            if (knowledgeTokens + sourceTokens <= config.knowledgeCap()) {
                keptSources.add(source);
                keptChunkIds.add(source.chunkId());
                knowledgeTokens += sourceTokens;
            }
        }

        while (memoryTokens + knowledgeTokens > config.totalCap() && !keptSources.isEmpty()) {
            SourceSnippet removed = keptSources.remove(keptSources.size() - 1);
            keptChunkIds.remove(removed.chunkId());
            knowledgeTokens -= knowledgeTokens(removed);
        }
        while (memoryTokens + knowledgeTokens > config.totalCap() && !keptMemory.isEmpty()) {
            MemoryContextItem removed = keptMemory.remove(keptMemory.size() - 1);
            keptMemoryIds.remove(removed.memoryId());
            memoryTokens -= memoryTokens(removed);
        }
        long totalTokens = memoryTokens + knowledgeTokens;
        if (totalTokens > config.totalCap()) {
            throw new ContextBudgetExceededException(
                    "Injected context exceeds total token cap after trimming",
                    totalTokens,
                    config.totalCap());
        }

        List<UUID> trimmedMemoryIds = safeMemory.items().stream()
                .map(MemoryContextItem::memoryId)
                .filter(id -> !keptMemoryIds.contains(id))
                .toList();
        List<String> trimmedKnowledgeChunkIds = safeSources.stream()
                .map(SourceSnippet::chunkId)
                .filter(id -> !keptChunkIds.contains(id))
                .toList();

        MemoryContextResponse trimmedMemory = rebuildMemoryContext(safeMemory, keptMemory, memoryTokens,
                !trimmedMemoryIds.isEmpty());
        return new TrimResult(
                List.copyOf(keptSources),
                trimmedMemory,
                memoryTokens,
                knowledgeTokens,
                totalTokens,
                !trimmedMemoryIds.isEmpty() || !trimmedKnowledgeChunkIds.isEmpty(),
                trimmedMemoryIds,
                trimmedKnowledgeChunkIds);
    }

    private static void rejectCatastrophicItems(List<SourceSnippet> sources, MemoryContextResponse memoryContext,
            BudgetConfig config) {
        long catastrophicLimit = (long) config.totalCap() * CATASTROPHIC_MULTIPLIER;
        sources.stream()
                .filter(source -> knowledgeTokens(source) > catastrophicLimit)
                .findFirst()
                .ifPresent(source -> {
                    long tokens = knowledgeTokens(source);
                    throw new ContextBudgetExceededException(
                            "Knowledge chunk exceeds catastrophic context budget: " + source.chunkId(),
                            tokens,
                            catastrophicLimit);
                });
        memoryContext.items().stream()
                .filter(item -> memoryTokens(item) > catastrophicLimit)
                .findFirst()
                .ifPresent(item -> {
                    long tokens = memoryTokens(item);
                    throw new ContextBudgetExceededException(
                            "Memory item exceeds catastrophic context budget: " + item.memoryId(),
                            tokens,
                            catastrophicLimit);
                });
    }

    private static MemoryContextResponse rebuildMemoryContext(MemoryContextResponse original,
            List<MemoryContextItem> keptMemory, long memoryTokens, boolean trimmed) {
        Map<String, Integer> scopes = emptyScopes();
        for (MemoryContextItem item : keptMemory) {
            if (item.scope() != null) {
                scopes.compute(item.scope().value(), (key, value) -> value == null ? 1 : value + 1);
            }
        }
        return new MemoryContextResponse(
                List.copyOf(keptMemory),
                keptMemory.stream().map(MemoryContextItem::memoryId).toList(),
                keptMemory.size(),
                Math.toIntExact(Math.min(Integer.MAX_VALUE, memoryTokens)),
                scopes,
                (int) keptMemory.stream().filter(MemoryContextItem::stale).count(),
                trimmed ? 0 : original.conflictFlaggedCount(),
                original.relevanceFilteredCount(),
                original.latencyMs());
    }

    private static Map<String, Integer> emptyScopes() {
        Map<String, Integer> scopes = new LinkedHashMap<>();
        for (MemoryScope scope : List.of(MemoryScope.USER, MemoryScope.GLOBAL, MemoryScope.PROJECT, MemoryScope.EPISODIC)) {
            scopes.put(scope.value(), 0);
        }
        return scopes;
    }

    private static long memoryTokens(MemoryContextItem item) {
        if (item.tokenEstimate() > 0) {
            return item.tokenEstimate();
        }
        // Prompt rendering injects promptText(), so the defensive fallback must size the compact text,
        // not the full memory text, to stay aligned with the rendered prompt cost.
        return TokenEstimator.estimate(item.promptText());
    }

    private static long knowledgeTokens(SourceSnippet source) {
        return TokenEstimator.estimate(source.snippet());
    }
}
