package com.mbworldwideapps.aiorchestration.modules.rules;

import java.util.UUID;

public record RuleDeprecationRequest(
        UUID ruleId,
        int expectedCurrentVersion,
        String expectedCurrentContentHash,
        String reason,
        String expectedApprovalContentHash,
        String expectedConfirmationCardHash,
        String workflowContractVersion,
        RuleHumanApprovalEvidence approval) {
}
