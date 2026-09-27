package com.mbworldwideapps.aiorchestration.modules.memoryai;

import java.util.List;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public record EditMemoryRequest(
        String summary,
        String text,
        List<String> tags,
        @Min(0) @Max(1) Double confidence,
        String scope,
        String projectKey,
        String sourceRef,
        List<MemoryCodeLocator> codeLocators,
        String actor,
        String reason) {

    public EditMemoryRequest(String summary, String text, List<String> tags,
            Double confidence, String scope, String projectKey, String sourceRef, String actor, String reason) {
        this(summary, text, tags, confidence, scope, projectKey, sourceRef, null, actor, reason);
    }

    public EditMemoryRequest(String summary, String text, List<String> tags,
            Double confidence, String actor, String reason) {
        this(summary, text, tags, confidence, null, null, null, null, actor, reason);
    }
}
