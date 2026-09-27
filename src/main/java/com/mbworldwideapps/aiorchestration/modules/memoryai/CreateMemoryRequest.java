package com.mbworldwideapps.aiorchestration.modules.memoryai;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreateMemoryRequest(
        @NotNull MemoryScope scope,
        String projectKey,
        @NotNull MemoryType memoryType,
        @NotBlank String summary,
        @NotBlank String text,
        List<String> tags,
        @Min(0) @Max(1) Double confidence,
        MemoryStatus status,
        @NotNull MemorySourceType sourceType,
        String sourceRef,
        String owner,
        Map<String, Object> metadata,
        Instant lastVerifiedAt,
        Instant expiresAt) {
}
