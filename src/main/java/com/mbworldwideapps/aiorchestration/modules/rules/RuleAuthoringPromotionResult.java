package com.mbworldwideapps.aiorchestration.modules.rules;

import java.util.UUID;

public record RuleAuthoringPromotionResult(
        UUID draftId,
        String projectKey,
        String candidateHash,
        UUID ruleId,
        int ruleVersion,
        String approvalContentHash,
        boolean replayed) {
}
