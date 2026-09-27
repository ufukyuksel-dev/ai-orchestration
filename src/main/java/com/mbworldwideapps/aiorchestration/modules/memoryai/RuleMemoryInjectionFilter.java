package com.mbworldwideapps.aiorchestration.modules.memoryai;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import com.mbworldwideapps.aiorchestration.config.RulesProperties;
import org.springframework.stereotype.Component;

/**
 * Single authority filter for every automatic memory-injection lane. While the
 * rule compiler is enabled, memory rows that already back immutable rule
 * versions are resolved through rule snapshots instead of being injected a
 * second time as generic memory. Disabling the rollout flag restores the legacy
 * memory behavior without mutating the evidence rows.
 */
@Component
public class RuleMemoryInjectionFilter {

    private final boolean enabled;
    private final RuleMemoryLinkLookup linkLookup;

    public RuleMemoryInjectionFilter(RulesProperties properties, RuleMemoryLinkLookup linkLookup) {
        this.enabled = Objects.requireNonNull(properties, "properties").enabled();
        this.linkLookup = Objects.requireNonNull(linkLookup, "linkLookup");
    }

    public Set<UUID> excludedIds(Collection<MemoryItem> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return Set.of();
        }
        return excludedMemoryIds(candidates.stream()
                .filter(Objects::nonNull)
                .filter(item -> item.memoryType() == MemoryType.RULE)
                .map(MemoryItem::id)
                .toList());
    }

    public Set<UUID> excludedMemoryIds(Collection<UUID> memoryIds) {
        if (memoryIds == null || memoryIds.isEmpty()) {
            return Set.of();
        }
        return linkLookup.excludedFromAutomaticInjection(memoryIds, enabled);
    }

    public List<MemoryItem> eligibleForAutomaticInjection(Collection<MemoryItem> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        Set<UUID> excluded = excludedIds(candidates);
        return candidates.stream()
                .filter(Objects::nonNull)
                .filter(item -> !excluded.contains(item.id()))
                .toList();
    }

    public boolean eligibleForAutomaticInjection(UUID memoryId, MemoryType memoryType) {
        if (memoryType != MemoryType.RULE) {
            return true;
        }
        return memoryId != null
                && !excludedMemoryIds(List.of(memoryId)).contains(memoryId);
    }

    public boolean enabled() {
        return enabled;
    }
}
