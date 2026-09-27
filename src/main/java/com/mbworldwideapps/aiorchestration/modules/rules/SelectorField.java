package com.mbworldwideapps.aiorchestration.modules.rules;

import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum SelectorField {
    PATH("path"),
    SYMBOL("symbol"),
    ROLE("role"),
    ANNOTATION("annotation"),
    SOURCE_SET("source_set"),
    GENERATED("generated"),
    ARTIFACT_KIND("artifact_kind"),
    LANGUAGE("language"),
    OPERATION("operation"),
    CAPSULE("capsule"),
    CAPSULE_LAYER("capsule_layer"),
    INTENT_KIND("intent_kind");

    private final String value;

    SelectorField(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static SelectorField from(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (SelectorField field : values()) {
            if (field.value.equals(normalized)) {
                return field;
            }
        }
        throw new IllegalArgumentException("Unsupported selector field: " + value);
    }
}
