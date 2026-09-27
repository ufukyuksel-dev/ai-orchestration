package com.mbworldwideapps.aiorchestration.modules.memoryai;

import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum MemoryType {
    RULE("rule"),
    PREFERENCE("preference"),
    CORRECTION("correction"),
    DECISION("decision"),
    ANTI_PATTERN("anti_pattern"),
    DISCOVERY("discovery");

    private final String value;

    MemoryType(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static MemoryType from(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (MemoryType type : values()) {
            if (type.value.equals(normalized)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unsupported memory type: " + value);
    }
}
