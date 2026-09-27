package com.mbworldwideapps.aiorchestration.modules.rules;

import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum RuleEnforcement {
    ADVISORY("advisory"),
    CONTEXT("context"),
    GATE("gate"),
    INSTRUCTION("instruction");

    private final String value;

    RuleEnforcement(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static RuleEnforcement from(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (RuleEnforcement enforcement : values()) {
            if (enforcement.value.equals(normalized)) {
                return enforcement;
            }
        }
        throw new IllegalArgumentException("Unsupported rule enforcement: " + value);
    }
}
