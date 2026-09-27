package com.mbworldwideapps.aiorchestration.modules.memoryai;

import java.util.List;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record AutoCuratedMemoryCandidate(
        @NotNull MemoryType memoryType,
        @NotBlank @Size(max = MemoryAtomLimits.SUMMARY_MAX_CHARS) String summary,
        @NotBlank @Size(max = MemoryAtomLimits.TEXT_MAX_CHARS) String text,
        @NotEmpty @Size(min = 1, max = 10) List<@NotBlank String> tags,
        @DecimalMin("0.0") @DecimalMax("1.0") double confidence,
        @NotBlank @Size(max = 1000) String reasoning) {

    public AutoCuratedMemoryCandidate {
        tags = tags == null ? List.of() : List.copyOf(tags);
    }
}
