package com.mbworldwideapps.aiorchestration.modules.memoryai;

import jakarta.validation.constraints.NotNull;

public record PromoteMemoryRequest(
        @NotNull MemoryScope scope,
        String projectKey,
        String actor,
        String reason) {
}
