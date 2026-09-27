package com.mbworldwideapps.aiorchestration.modules.rules;

import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum RuleProvenance {
    HUMAN("human"),
    DIRECT_HUMAN_POLICY("direct_human_policy"),
    REVIEW_MINING("review_mining"),
    INCIDENT("incident"),
    CURATED("curated");

    private final String value;

    RuleProvenance(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static RuleProvenance from(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (RuleProvenance provenance : values()) {
            if (provenance.value.equals(normalized)) {
                return provenance;
            }
        }
        throw new IllegalArgumentException("Unsupported rule provenance: " + value);
    }
}
