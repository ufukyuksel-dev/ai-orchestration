package com.mbworldwideapps.aiorchestration.modules.memoryai;

import jakarta.validation.constraints.NotBlank;

public record CaptureMemoryRequest(
        @NotBlank String text,
        MemorySignalType signalType,
        String sessionId,
        String projectKey,
        String userId) {
}
