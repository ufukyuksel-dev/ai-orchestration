package com.mbworldwideapps.aiorchestration.modules.memoryai;

import java.util.UUID;

public record MemoryInlineApprovalDecisionResponse(
        UUID originalMemoryId,
        String decision,
        MemoryItem item,
        MemoryItem replacement,
        String actor) {
}
