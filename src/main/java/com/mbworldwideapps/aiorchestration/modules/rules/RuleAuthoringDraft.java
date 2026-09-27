package com.mbworldwideapps.aiorchestration.modules.rules;

import java.time.Instant;
import java.util.UUID;

/** Stored immutable candidate plus its optional one-way promotion binding. */
public record RuleAuthoringDraft(
        UUID id,
        String projectKey,
        String candidateJson,
        String candidateHash,
        String createdBy,
        Instant createdAt,
        UUID promotedRuleId,
        Integer promotedRuleVersion,
        String promotedApprovalHash,
        String promotedRequestHash,
        Instant promotedAt) {

    public RuleAuthoringDraft {
        if (id == null || candidateJson == null || candidateJson.isBlank()
                || !isSha256(candidateHash) || createdBy == null || createdBy.isBlank() || createdAt == null) {
            throw new IllegalArgumentException("complete rule authoring draft is required");
        }
        boolean unpromoted = promotedRuleId == null && promotedRuleVersion == null
                && promotedApprovalHash == null && promotedRequestHash == null && promotedAt == null;
        boolean promoted = promotedRuleId != null && promotedRuleVersion != null && promotedRuleVersion > 0
                && isSha256(promotedApprovalHash) && isSha256(promotedRequestHash) && promotedAt != null;
        if (!unpromoted && !promoted) {
            throw new IllegalArgumentException("rule authoring promotion binding is incomplete");
        }
    }

    public boolean promoted() {
        return promotedRuleId != null;
    }

    private static boolean isSha256(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }
}
