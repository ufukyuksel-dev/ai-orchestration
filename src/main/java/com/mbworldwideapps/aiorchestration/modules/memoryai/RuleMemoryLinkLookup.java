package com.mbworldwideapps.aiorchestration.modules.memoryai;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;

/** Narrow memory-owned view of whether a RULE memory already backs immutable rule evidence. */
@FunctionalInterface
public interface RuleMemoryLinkLookup {

    boolean hasLinkedDefinition(UUID memoryId);

    default Set<UUID> linkedMemoryIds(Collection<UUID> memoryIds) {
        if (memoryIds == null || memoryIds.isEmpty()) {
            return Set.of();
        }
        return memoryIds.stream()
                .filter(java.util.Objects::nonNull)
                .distinct()
                .filter(this::hasLinkedDefinition)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /**
     * Returns generic-memory candidates that must be suppressed. With rule
     * authority enabled every linked origin is suppressed. With the rollout
     * disabled nothing is suppressed, preserving the pre-authority behavior.
     */
    default Set<UUID> excludedFromAutomaticInjection(Collection<UUID> memoryIds,
            boolean ruleAuthorityEnabled) {
        return ruleAuthorityEnabled ? linkedMemoryIds(memoryIds) : Set.of();
    }
}
