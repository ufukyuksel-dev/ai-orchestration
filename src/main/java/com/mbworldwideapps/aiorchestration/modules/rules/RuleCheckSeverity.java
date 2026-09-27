package com.mbworldwideapps.aiorchestration.modules.rules;

import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum RuleCheckSeverity {
    ADVISORY("advisory"),
    REQUIRED("required"),
    GATE("gate");

    private final String value;

    RuleCheckSeverity(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static RuleCheckSeverity from(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (RuleCheckSeverity severity : values()) {
            if (severity.value.equals(normalized)) {
                return severity;
            }
        }
        throw new IllegalArgumentException("Unsupported rule check severity: " + value);
    }
}
