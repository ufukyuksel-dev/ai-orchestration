package com.mbworldwideapps.aiorchestration.modules.memoryai;

import java.util.Map;

public record MemoryInlineApprovalTurnResponse(
        String status,
        boolean decisionApplied,
        String markdown,
        MemoryConfirmResponse decision,
        Map<String, Object> metadata) {

    public static MemoryInlineApprovalTurnResponse noDecision() {
        return new MemoryInlineApprovalTurnResponse("no_decision", false, "", null, Map.of());
    }
}
