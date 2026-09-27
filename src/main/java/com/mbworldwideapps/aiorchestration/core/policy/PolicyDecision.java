package com.mbworldwideapps.aiorchestration.core.policy;

public record PolicyDecision(boolean allowed, String reason) {

    public static PolicyDecision allow() {
        return new PolicyDecision(true, "allowed");
    }

    public static PolicyDecision deny(String reason) {
        return new PolicyDecision(false, reason);
    }
}
