package com.mbworldwideapps.aiorchestration.modules.rules;

import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum SelectorPolarity {
    INCLUDE("include"),
    EXCLUDE("exclude");

    private final String value;

    SelectorPolarity(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static SelectorPolarity from(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (SelectorPolarity polarity : values()) {
            if (polarity.value.equals(normalized)) {
                return polarity;
            }
        }
        throw new IllegalArgumentException("Unsupported selector polarity: " + value);
    }
}
