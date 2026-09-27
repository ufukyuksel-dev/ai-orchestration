package com.mbworldwideapps.aiorchestration.modules.llmgateway;

public enum DegradedReason {
    NONE,
    TIMEOUT,
    PROVIDER_ERROR,
    PREFLIGHT_FAILED,
    BUDGET_EXCEEDED
}
