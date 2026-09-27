package com.mbworldwideapps.aiorchestration.modules.memoryai;

import jakarta.validation.constraints.NotNull;

public record MemoryStatusUpdateRequest(
        @NotNull MemoryStatus status,
        String actor,
        String reason) {
}
