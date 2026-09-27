package com.mbworldwideapps.aiorchestration.modules.memoryai;

import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum ReviewReason {
    PROMOTION_CANDIDATE("promotion_candidate"),
    CONFLICT("conflict"),
    POLLUTION_RISK("pollution_risk"),
    LOW_CONFIDENCE("low_confidence");

    private final String value;

    ReviewReason(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static ReviewReason from(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (ReviewReason reason : values()) {
            if (reason.value.equals(normalized)) {
                return reason;
            }
        }
        throw new IllegalArgumentException("Unsupported review reason: " + value);
    }
}
