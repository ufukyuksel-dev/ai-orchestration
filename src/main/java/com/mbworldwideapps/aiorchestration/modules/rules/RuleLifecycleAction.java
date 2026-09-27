package com.mbworldwideapps.aiorchestration.modules.rules;

public enum RuleLifecycleAction {
    PROMOTED("promoted"),
    DEPRECATED("deprecated");

    private final String value;

    RuleLifecycleAction(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static RuleLifecycleAction from(String value) {
        for (RuleLifecycleAction action : values()) {
            if (action.value.equalsIgnoreCase(value)) {
                return action;
            }
        }
        throw new IllegalArgumentException("Unsupported rule lifecycle action: " + value);
    }
}
