package com.mbworldwideapps.aiorchestration.modules.rules;

import java.util.UUID;

public record RuleAuthoringPreviewResult(
        UUID draftId,
        String projectKey,
        String candidateHash,
        String workflowContractVersion,
        String versionContentHash,
        String approvalContentHash,
        String confirmationCard,
        String confirmationCardHash) {
}
