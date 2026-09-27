package com.mbworldwideapps.aiorchestration.modules.rules;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public record RuleCheckSpec(
        RuleCheckPhase phase,
        String checkerType,
        RuleCheckSeverity severity,
        Map<String, Object> config) {

    public RuleCheckSpec {
        Objects.requireNonNull(phase, "rule check phase");
        Objects.requireNonNull(severity, "rule check severity");
        if (checkerType == null || checkerType.isBlank()) {
            throw new IllegalArgumentException("rule checker type is required");
        }
        checkerType = checkerType.trim().toLowerCase(Locale.ROOT);
        if (!checkerType.matches("[a-z][a-z0-9_]{0,127}")) {
            throw new IllegalArgumentException("rule checker type is not canonical");
        }
        config = RuleImmutableValues.immutableMap(config == null ? Map.of() : config);
    }
}
