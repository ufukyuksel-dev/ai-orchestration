package com.mbworldwideapps.aiorchestration.modules.rules;

import java.time.Instant;
import java.util.UUID;

public record RuleTargetBinding(
        UUID id,
        UUID ruleId,
        int ruleVersion,
        BindingKind kind,
        String targetKey,
        Instant createdAt) {
}
