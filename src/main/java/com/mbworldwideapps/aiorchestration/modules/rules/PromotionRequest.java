package com.mbworldwideapps.aiorchestration.modules.rules;

/**
 * Hash-bound promotion command. The hashes must come from the server-side
 * {@link RulePromotionPreview} for the exact candidate the human saw.
 */
public record PromotionRequest(
        RulePromotionCandidate candidate,
        String expectedApprovalContentHash,
        String expectedConfirmationCardHash,
        String workflowContractVersion,
        RuleHumanApprovalEvidence approval) {
}
