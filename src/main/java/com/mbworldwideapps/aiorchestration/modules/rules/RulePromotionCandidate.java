package com.mbworldwideapps.aiorchestration.modules.rules;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Typed, non-executable rule candidate. A candidate becomes authoritative only
 * after {@link RulePromotionService#preview(RulePromotionCandidate)} has produced
 * the exact hashes later supplied to promotion with human evidence.
 */
public record RulePromotionCandidate(
        UUID originMemoryId,
        String expectedOriginContentHash,
        String projectKey,
        String statement,
        String rationale,
        RuleEnforcement enforcement,
        boolean appliesAll,
        String detectorType,
        Map<String, Object> detectorConfig,
        List<TargetBindingRequest> targets,
        List<RuleSelectorGroup> selectorGroups,
        List<RuleCheckSpec> checks,
        RuleProvenance provenance) {

    public RulePromotionCandidate {
        detectorConfig = RuleImmutableValues.immutableMap(detectorConfig);
        targets = targets == null ? List.of() : List.copyOf(targets);
        selectorGroups = selectorGroups == null ? List.of() : List.copyOf(selectorGroups);
        checks = checks == null ? List.of() : List.copyOf(checks);
    }

    /** Backward-compatible authority-v1 candidate shape. */
    public RulePromotionCandidate(UUID originMemoryId, String expectedOriginContentHash, String projectKey,
            String statement, String rationale, RuleEnforcement enforcement, boolean appliesAll,
            String detectorType, Map<String, Object> detectorConfig, List<TargetBindingRequest> targets,
            RuleProvenance provenance) {
        this(originMemoryId, expectedOriginContentHash, projectKey, statement, rationale, enforcement,
                appliesAll, detectorType, detectorConfig, targets, List.of(), List.of(), provenance);
    }

    public record TargetBindingRequest(BindingKind kind, String targetKey) {
    }
}
