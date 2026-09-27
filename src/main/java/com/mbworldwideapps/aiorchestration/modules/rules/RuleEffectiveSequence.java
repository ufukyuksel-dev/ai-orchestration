package com.mbworldwideapps.aiorchestration.modules.rules;

import java.util.LinkedHashMap;
import java.util.Map;

/** Sequence values after one atomic effective-reach mutation. */
public record RuleEffectiveSequence(long legacySeq, long globalSeq, Map<String, Long> projectSeqs) {

    public RuleEffectiveSequence {
        if (legacySeq < 0 || globalSeq < 0) {
            throw new IllegalArgumentException("rule sequence values must be non-negative");
        }
        Map<String, Long> normalized = new LinkedHashMap<>();
        if (projectSeqs != null) {
            projectSeqs.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
                if (entry.getKey() == null || entry.getKey().isBlank()
                        || entry.getValue() == null || entry.getValue() < 0) {
                    throw new IllegalArgumentException("project rule sequence entries must be complete and non-negative");
                }
                normalized.put(entry.getKey(), entry.getValue());
            });
        }
        projectSeqs = java.util.Collections.unmodifiableMap(normalized);
    }
}
