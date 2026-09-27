package com.mbworldwideapps.aiorchestration.modules.memoryai;

import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum MemoryScope {
    GLOBAL("global"),
    PROJECT("project"),
    USER("user"),
    EPISODIC("episodic");

    private final String value;

    MemoryScope(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static MemoryScope from(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (MemoryScope scope : values()) {
            if (scope.value.equals(normalized)) {
                return scope;
            }
        }
        throw new IllegalArgumentException("Unsupported memory scope: " + value);
    }
}
