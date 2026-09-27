package com.mbworldwideapps.aiorchestration.modules.rules;

import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum BindingKind {
    FILE("file"),
    SYMBOL("symbol"),
    CAPSULE("capsule"),
    PATH_GLOB("path_glob");

    private final String value;

    BindingKind(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static BindingKind from(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (BindingKind kind : values()) {
            if (kind.value.equals(normalized)) {
                return kind;
            }
        }
        throw new IllegalArgumentException("Unsupported binding kind: " + value);
    }
}
