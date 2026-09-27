package com.mbworldwideapps.aiorchestration.modules.memoryai;

import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum MemoryStatus {
    ACTIVE("active"),
    PENDING_REVIEW("pending_review"),
    REJECTED("rejected"),
    ARCHIVED("archived"),
    STALE("stale");

    private final String value;

    MemoryStatus(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static MemoryStatus from(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (MemoryStatus status : values()) {
            if (status.value.equals(normalized)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unsupported memory status: " + value);
    }
}
