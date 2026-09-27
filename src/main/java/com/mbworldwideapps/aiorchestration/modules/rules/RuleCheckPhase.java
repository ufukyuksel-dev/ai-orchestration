package com.mbworldwideapps.aiorchestration.modules.rules;

import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum RuleCheckPhase {
    PLAN("plan"),
    DIFF("diff"),
    AST("ast"),
    POST_TEST("post_test");

    private final String value;

    RuleCheckPhase(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static RuleCheckPhase from(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (RuleCheckPhase phase : values()) {
            if (phase.value.equals(normalized)) {
                return phase;
            }
        }
        throw new IllegalArgumentException("Unsupported rule check phase: " + value);
    }
}
