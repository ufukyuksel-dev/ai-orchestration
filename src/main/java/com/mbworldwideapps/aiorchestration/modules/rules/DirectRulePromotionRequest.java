package com.mbworldwideapps.aiorchestration.modules.rules;

import java.util.UUID;

/** Exact hash-bound request for promoting one immutable direct-human draft. */
public record DirectRulePromotionRequest(
        UUID draftId,
        String expectedProjectKey,
        String expectedCandidateHash,
        String expectedApprovalContentHash,
        String expectedConfirmationCardHash,
        String workflowContractVersion,
        String humanRawText,
        String humanTurnRef,
        boolean aiInterpretedAsApproval,
        double agentConfidence) {
}
