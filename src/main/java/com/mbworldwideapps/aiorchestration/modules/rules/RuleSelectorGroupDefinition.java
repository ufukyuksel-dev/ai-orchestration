package com.mbworldwideapps.aiorchestration.modules.rules;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Immutable persistence shape for a canonical selector group and its predicates. */
public record RuleSelectorGroupDefinition(
        UUID id,
        UUID ruleId,
        int ruleVersion,
        String groupKey,
        int ordinal,
        List<RuleSelectorPredicateDefinition> predicates,
        Instant createdAt) {

    public RuleSelectorGroupDefinition {
        Objects.requireNonNull(id, "selector group id");
        Objects.requireNonNull(ruleId, "selector rule id");
        if (ruleVersion < 1 || groupKey == null || groupKey.isBlank()
                || ordinal < 0 || ordinal > 255) {
            throw new IllegalArgumentException("complete bounded selector group identity is required");
        }
        predicates = predicates == null ? List.of() : List.copyOf(predicates);
        if (predicates.isEmpty()) {
            throw new IllegalArgumentException("selector group predicates are required");
        }
    }
}
