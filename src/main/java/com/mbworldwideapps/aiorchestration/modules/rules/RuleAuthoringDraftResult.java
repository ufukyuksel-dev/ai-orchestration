package com.mbworldwideapps.aiorchestration.modules.rules;

import java.util.UUID;

public record RuleAuthoringDraftResult(
        UUID draftId,
        String projectKey,
        String candidateHash,
        String workflowContractVersion,
        String summary) {
}
