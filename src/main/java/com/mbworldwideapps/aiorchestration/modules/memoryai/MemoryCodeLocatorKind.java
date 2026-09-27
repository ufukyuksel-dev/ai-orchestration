package com.mbworldwideapps.aiorchestration.modules.memoryai;

import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum MemoryCodeLocatorKind {
    FILE("file"),
    DIRECTORY("directory"),
    SYMBOL("symbol"),
    CAPSULE("capsule");

    private final String value;

    MemoryCodeLocatorKind(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static MemoryCodeLocatorKind from(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("memory code locator kind is required");
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (MemoryCodeLocatorKind candidate : values()) {
            if (candidate.value.equals(normalized) || candidate.name().equalsIgnoreCase(normalized)) {
                return candidate;
            }
        }
        throw new IllegalArgumentException("Unsupported memory code locator kind: " + value);
    }
}
