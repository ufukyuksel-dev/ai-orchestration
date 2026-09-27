package com.mbworldwideapps.aiorchestration.modules.rules;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Immutable, version-bound checker definition with its executable contract hash. */
public record RuleCheckDefinition(
        UUID id,
        UUID ruleId,
        int ruleVersion,
        RuleCheckPhase phase,
        String checkerType,
        String implementationVersion,
        String configSchemaVersion,
        RuleCheckSeverity severity,
        Map<String, Object> config,
        String checkerContractHash,
        int ordinal,
        Instant createdAt) {

    public RuleCheckDefinition {
        Objects.requireNonNull(id, "rule check id");
        Objects.requireNonNull(ruleId, "rule check rule id");
        Objects.requireNonNull(phase, "rule check phase");
        Objects.requireNonNull(severity, "rule check severity");
        if (ruleVersion < 1 || isBlank(checkerType) || isBlank(implementationVersion)
                || isBlank(configSchemaVersion) || checkerContractHash == null
                || !checkerContractHash.matches("[0-9a-f]{64}") || ordinal < 0 || ordinal > 255) {
            throw new IllegalArgumentException("complete bounded rule checker definition is required");
        }
        config = RuleImmutableValues.immutableMap(config == null ? Map.of() : config);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
