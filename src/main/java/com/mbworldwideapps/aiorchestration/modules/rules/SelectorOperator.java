package com.mbworldwideapps.aiorchestration.modules.rules;

import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum SelectorOperator {
    EQUALS("equals"),
    IN("in"),
    GLOB("glob"),
    PRESENT("present");

    private final String value;

    SelectorOperator(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static SelectorOperator from(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (SelectorOperator operator : values()) {
            if (operator.value.equals(normalized)) {
                return operator;
            }
        }
        throw new IllegalArgumentException("Unsupported selector operator: " + value);
    }
}
