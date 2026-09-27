package com.mbworldwideapps.aiorchestration.modules.memoryai;

import java.util.List;

public record EpisodicCandidateClassification(
        boolean accepted,
        String rejectReason,
        MemoryScope scope,
        MemoryType memoryType,
        double confidence,
        String summary,
        List<String> tags,
        int tokenEstimate,
        String classifierReason) {
}
