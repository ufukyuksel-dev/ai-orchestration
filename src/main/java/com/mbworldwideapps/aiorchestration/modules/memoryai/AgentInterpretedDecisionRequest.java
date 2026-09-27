package com.mbworldwideapps.aiorchestration.modules.memoryai;

import java.util.UUID;

public record AgentInterpretedDecisionRequest(
        String projectKey,
        String sessionId,
        UUID memoryId,
        String decision,
        Boolean aiInterpretedAsApproval,
        String humanRawText,
        Double agentConfidence,
        String humanTurnRef,
        String editedText,
        String reason) {
}
