package com.mbworldwideapps.aiorchestration.modules.rules;

import java.time.Instant;
import java.util.UUID;

public record RuleLifecycleEvent(
        UUID id,
        UUID ruleId,
        int ruleVersion,
        RuleLifecycleAction action,
        String actor,
        String humanTurnRef,
        String humanRawTextHash,
        String reason,
        String approvalContentHash,
        Instant createdAt) {
}
