package com.mbworldwideapps.aiorchestration.modules.rules;

import java.util.List;
import java.util.Map;

/** MCP-safe direct-human candidate shape; origin and approval authority stay server-owned. */
public record DirectRuleDraftRequest(
        String statement,
        String rationale,
        RuleEnforcement enforcement,
        boolean appliesAll,
        String detectorType,
        Map<String, Object> detectorConfig,
        List<RulePromotionCandidate.TargetBindingRequest> targets,
        List<RuleSelectorGroup> selectorGroups,
        List<RuleCheckSpec> checks) {

    public DirectRuleDraftRequest {
        detectorConfig = RuleImmutableValues.immutableMap(detectorConfig);
        targets = targets == null ? List.of() : List.copyOf(targets);
        selectorGroups = selectorGroups == null ? List.of() : List.copyOf(selectorGroups);
        checks = checks == null ? List.of() : List.copyOf(checks);
    }

    RulePromotionCandidate toCandidate(String projectKey) {
        return new RulePromotionCandidate(null, null, projectKey, statement, rationale, enforcement,
                appliesAll, detectorType, detectorConfig, targets, selectorGroups, checks,
                RuleProvenance.DIRECT_HUMAN_POLICY);
    }

    static DirectRuleDraftRequest fromCandidate(RulePromotionCandidate candidate) {
        return new DirectRuleDraftRequest(candidate.statement(), candidate.rationale(), candidate.enforcement(),
                candidate.appliesAll(), candidate.detectorType(), candidate.detectorConfig(), candidate.targets(),
                candidate.selectorGroups(), candidate.checks());
    }
}
