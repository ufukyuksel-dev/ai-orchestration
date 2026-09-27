package com.mbworldwideapps.aiorchestration.modules.rules;

/** Exact server-side preview for a rule deprecation decision. */
public record RuleDeprecationPreview(
        String workflowContractVersion,
        String approvalContentHash,
        String confirmationCard,
        String confirmationCardHash) {
}
