package com.mbworldwideapps.aiorchestration.modules.rules;

import java.text.Normalizer;
import java.util.Comparator;
import java.util.List;

public record RuleSelectorGroup(String groupKey, List<RuleSelectorPredicate> predicates) {

    public static final int MAX_PREDICATES = 256;

    public RuleSelectorGroup {
        if (groupKey == null || groupKey.isBlank()) {
            throw new IllegalArgumentException("selector group key is required");
        }
        groupKey = Normalizer.normalize(groupKey.trim(), Normalizer.Form.NFC);
        if (groupKey.length() > 128) {
            throw new IllegalArgumentException("selector group key exceeds 128 characters");
        }
        if (predicates == null || predicates.isEmpty() || predicates.size() > MAX_PREDICATES) {
            throw new IllegalArgumentException("selector group requires between 1 and "
                    + MAX_PREDICATES + " predicates");
        }
        predicates = predicates.stream().sorted(Comparator
                .comparing((RuleSelectorPredicate predicate) -> predicate.polarity().value())
                .thenComparing(predicate -> predicate.field().value())
                .thenComparing(predicate -> predicate.operator().value())
                .thenComparing(predicate -> String.join("\u0000", predicate.values())))
                .toList();
        if (predicates.stream().noneMatch(predicate -> predicate.polarity() == SelectorPolarity.INCLUDE)) {
            throw new IllegalArgumentException("selector group requires at least one INCLUDE predicate");
        }
        if (predicates.stream().distinct().count() != predicates.size()) {
            throw new IllegalArgumentException("duplicate selector predicates are not allowed");
        }
    }
}
