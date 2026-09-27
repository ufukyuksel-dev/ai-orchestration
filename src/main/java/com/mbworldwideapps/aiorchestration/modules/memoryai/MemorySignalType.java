package com.mbworldwideapps.aiorchestration.modules.memoryai;

import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum MemorySignalType {
    CORRECTION("correction"),
    APPROVAL("approval"),
    EXPLICIT("explicit"),
    NONE("none");

    private final String value;

    MemorySignalType(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static MemorySignalType from(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (MemorySignalType type : values()) {
            if (type.value.equals(normalized)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unsupported memory signal type: " + value);
    }
}
