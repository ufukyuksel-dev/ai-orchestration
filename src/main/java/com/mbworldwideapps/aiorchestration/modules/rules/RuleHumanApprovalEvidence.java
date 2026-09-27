package com.mbworldwideapps.aiorchestration.modules.rules;

/** Explicit human evidence attached to an exact server-rendered confirmation card. */
public record RuleHumanApprovalEvidence(
        String approvedBy,
        String humanTurnRef,
        String humanRawText,
        boolean aiInterpretedAsApproval,
        double agentConfidence) {
}
