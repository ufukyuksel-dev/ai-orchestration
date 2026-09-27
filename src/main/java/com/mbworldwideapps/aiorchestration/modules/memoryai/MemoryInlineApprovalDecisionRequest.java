package com.mbworldwideapps.aiorchestration.modules.memoryai;

import java.util.List;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record MemoryInlineApprovalDecisionRequest(
        @NotBlank String decision,
        String actor,
        String reason,
        String summary,
        String text,
        List<String> tags,
        @Min(0) @Max(1) Double confidence,
        String humanTurnRef) {
}
