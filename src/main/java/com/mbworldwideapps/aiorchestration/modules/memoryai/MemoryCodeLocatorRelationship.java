package com.mbworldwideapps.aiorchestration.modules.memoryai;

import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum MemoryCodeLocatorRelationship {
    EVIDENCES("evidences"),
    MENTIONS("mentions"),
    CONSTRAINS("constrains");

    private final String value;

    MemoryCodeLocatorRelationship(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static MemoryCodeLocatorRelationship from(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (MemoryCodeLocatorRelationship candidate : values()) {
            if (candidate.value.equals(normalized) || candidate.name().equalsIgnoreCase(normalized)) {
                return candidate;
            }
        }
        throw new IllegalArgumentException("Unsupported memory code locator relationship: " + value);
    }
}
