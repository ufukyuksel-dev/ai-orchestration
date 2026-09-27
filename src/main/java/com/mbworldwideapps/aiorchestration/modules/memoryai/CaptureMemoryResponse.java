package com.mbworldwideapps.aiorchestration.modules.memoryai;

import java.util.UUID;

public record CaptureMemoryResponse(
        boolean accepted,
        String decision,
        String rejectedReason,
        String signalType,
        UUID memoryId,
        String status,
        double confidence,
        String piiPattern,
        String textHashPrefix) {
}
