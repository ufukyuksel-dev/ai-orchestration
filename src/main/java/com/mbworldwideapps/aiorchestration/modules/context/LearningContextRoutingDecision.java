package com.mbworldwideapps.aiorchestration.modules.context;

record LearningContextRoutingDecision(
        LearningContextMode requestedMode,
        LearningContextMode effectiveMode,
        String reason,
        String memoryAbstentionReason) {

    LearningContextRoutingDecision {
        requestedMode = requestedMode == null ? LearningContextMode.NONE : requestedMode;
        effectiveMode = effectiveMode == null ? LearningContextMode.NONE : effectiveMode;
        reason = reason == null ? "" : reason;
        memoryAbstentionReason = memoryAbstentionReason == null ? "" : memoryAbstentionReason;
    }
}
