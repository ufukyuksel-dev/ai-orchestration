package com.mbworldwideapps.aiorchestration.modules.memoryai;

import java.time.Instant;
import java.util.Set;

public record MemoryEligibilityFilter(
        String projectKey,
        String userProjectKey,
        boolean includeGlobal,
        boolean includeEpisodic,
        Set<MemoryStatus> statuses,
        double minSimilarity,
        boolean excludePromotedRuleOrigins) {

    public MemoryEligibilityFilter(String projectKey, String userProjectKey, boolean includeGlobal,
            boolean includeEpisodic, Set<MemoryStatus> statuses) {
        this(projectKey, userProjectKey, includeGlobal, includeEpisodic, statuses, 0.0, false);
    }

    public MemoryEligibilityFilter(String projectKey, String userProjectKey, boolean includeGlobal,
            boolean includeEpisodic, Set<MemoryStatus> statuses, double minSimilarity) {
        this(projectKey, userProjectKey, includeGlobal, includeEpisodic, statuses, minSimilarity, false);
    }

    public MemoryEligibilityFilter {
        statuses = statuses == null ? Set.of() : Set.copyOf(statuses);
        minSimilarity = Math.max(0.0, Math.min(1.0, minSimilarity));
    }

    public static MemoryEligibilityFilter forRetrieve(String projectKey, String userProjectKey) {
        return forRetrieve(projectKey, userProjectKey, 0.0);
    }

    public static MemoryEligibilityFilter forRetrieve(String projectKey, String userProjectKey,
            double minSimilarity) {
        return new MemoryEligibilityFilter(projectKey, userProjectKey, true, true,
                Set.of(MemoryStatus.ACTIVE, MemoryStatus.STALE), minSimilarity, false);
    }

    public static MemoryEligibilityFilter forAutomaticInjection(String projectKey, String userProjectKey,
            double minSimilarity, boolean ruleAuthorityEnabled) {
        return new MemoryEligibilityFilter(projectKey, userProjectKey, true, true,
                Set.of(MemoryStatus.ACTIVE, MemoryStatus.STALE), minSimilarity, ruleAuthorityEnabled);
    }

    public boolean accepts(MemoryItem item) {
        if (item == null) {
            return false;
        }
        if (!statuses.isEmpty() && !statuses.contains(item.status())) {
            return false;
        }
        if (item.expiresAt() != null && !item.expiresAt().isAfter(Instant.now())) {
            return false;
        }
        if (excludePromotedRuleOrigins
                && item.memoryType() == MemoryType.RULE
                && item.metadata().containsKey("promotedRuleId")) {
            return false;
        }
        MemoryScope scope = item.scope();
        String itemProjectKey = item.projectKey();
        return switch (scope) {
            case USER -> userProjectKey != null && userProjectKey.equals(itemProjectKey);
            case PROJECT -> projectKey != null && projectKey.equals(itemProjectKey);
            case GLOBAL -> includeGlobal && itemProjectKey == null;
            case EPISODIC -> includeEpisodic;
        };
    }
}
