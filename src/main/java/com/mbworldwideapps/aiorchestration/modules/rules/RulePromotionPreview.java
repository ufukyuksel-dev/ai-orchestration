package com.mbworldwideapps.aiorchestration.modules.rules;

/** Exact server-side preview that must be shown and echoed during promotion. */
public record RulePromotionPreview(
        String workflowContractVersion,
        String versionContentHash,
        String approvalContentHash,
        String confirmationCard,
        String confirmationCardHash) {
}
