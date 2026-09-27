package com.mbworldwideapps.aiorchestration.modules.memoryai;

import java.util.UUID;

public record MemoryConfirmResponse(
        UUID memoryId,
        String status,
        String decision,
        boolean aiInterpretedAsApproval,
        MemoryItem replacement) {
}
