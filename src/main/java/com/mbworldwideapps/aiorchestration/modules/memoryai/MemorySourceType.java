package com.mbworldwideapps.aiorchestration.modules.memoryai;

import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum MemorySourceType {
    MANUAL("manual"),
    CORRECTION_SIGNAL("correction_signal"),
    APPROVAL_SIGNAL("approval_signal"),
    SESSION_SUMMARY("session_summary"),
    PROMOTION("promotion"),
    SCANNED("scanned"),
    AUTO_CURATED("auto_curated"),
    MCP_EXTERNAL("mcp_external");

    private final String value;

    MemorySourceType(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static MemorySourceType from(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (MemorySourceType type : values()) {
            if (type.value.equals(normalized)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unsupported memory source type: " + value);
    }
}
