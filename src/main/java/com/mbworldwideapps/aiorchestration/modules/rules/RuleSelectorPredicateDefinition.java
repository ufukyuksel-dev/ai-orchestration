package com.mbworldwideapps.aiorchestration.modules.rules;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Immutable persistence shape for one selector predicate. */
public record RuleSelectorPredicateDefinition(
        UUID id,
        UUID groupId,
        SelectorPolarity polarity,
        SelectorField field,
        SelectorOperator operator,
        List<String> values,
        int ordinal,
        Instant createdAt) {

    public RuleSelectorPredicateDefinition {
        Objects.requireNonNull(id, "selector predicate id");
        Objects.requireNonNull(groupId, "selector group id");
        Objects.requireNonNull(polarity, "selector polarity");
        Objects.requireNonNull(field, "selector field");
        Objects.requireNonNull(operator, "selector operator");
        values = values == null ? List.of() : List.copyOf(values);
        if (ordinal < 0 || ordinal > 255) {
            throw new IllegalArgumentException("selector predicate ordinal must be between 0 and 255");
        }
    }
}
