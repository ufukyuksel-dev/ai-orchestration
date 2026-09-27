package com.mbworldwideapps.aiorchestration.modules.rules;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Immutable version-scoped reach authority. */
public record RuleScopeAssignment(
        UUID id,
        UUID ruleId,
        int ruleVersion,
        RuleScopeType scopeType,
        String projectKey,
        UUID policySetId,
        Integer policySetVersion,
        String relationType,
        int ordinal,
        Instant createdAt) {

    public RuleScopeAssignment {
        Objects.requireNonNull(id, "scope assignment id");
        Objects.requireNonNull(ruleId, "scope assignment rule id");
        Objects.requireNonNull(scopeType, "scope assignment type");
        if (ruleVersion < 1 || ordinal < 0 || ordinal > 255) {
            throw new IllegalArgumentException("scope assignment version and ordinal are invalid");
        }
        boolean valid = switch (scopeType) {
            case GLOBAL -> projectKey == null && policySetId == null && policySetVersion == null
                    && relationType == null;
            case PROJECT -> !isBlank(projectKey) && policySetId == null && policySetVersion == null
                    && relationType == null;
            case PROJECT_SET -> projectKey == null && policySetId != null && policySetVersion != null
                    && policySetVersion > 0 && relationType == null;
            case RELATION -> projectKey == null && policySetId == null && policySetVersion == null
                    && !isBlank(relationType);
        };
        if (!valid) {
            throw new IllegalArgumentException("scope assignment fields do not match scope type");
        }
    }

    public static RuleScopeAssignment forProjectKey(
            UUID id, UUID ruleId, int ruleVersion, String projectKey, Instant createdAt) {
        if (projectKey != null && projectKey.isBlank()) {
            throw new IllegalArgumentException("project key must be null for global scope or non-blank");
        }
        RuleScopeType type = projectKey == null ? RuleScopeType.GLOBAL : RuleScopeType.PROJECT;
        return new RuleScopeAssignment(id, ruleId, ruleVersion, type,
                type == RuleScopeType.PROJECT ? projectKey : null, null, null, null, 0, createdAt);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
