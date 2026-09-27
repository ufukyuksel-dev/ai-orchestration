package com.mbworldwideapps.aiorchestration.modules.memoryai;

import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum MemoryEventType {
    CREATED("created"),
    UPDATED("updated"),
    STATUS_CHANGED("status_changed"),
    RETRIEVED("retrieved"),
    ACCEPTED("accepted"),
    REJECTED("rejected"),
    PROMOTED("promoted"),
    ARCHIVED("archived"),
    CONFLICT_DETECTED("conflict_detected"),
    AUTO_CURATED("auto_curated"),
    SHADOW_LOGGED("shadow_logged"),
    CURATION_SKIPPED("curation_skipped"),
    RELATION_JUDGED("relation_judged");

    private final String value;

    MemoryEventType(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static MemoryEventType from(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (MemoryEventType type : values()) {
            if (type.value.equals(normalized)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unsupported memory event type: " + value);
    }
}
