package com.mbworldwideapps.aiorchestration.modules.scanner;

import java.util.Map;
import java.util.UUID;

public record CodeCapsuleProjectionRecord(
        UUID id,
        String projectKey,
        UUID symbolId,
        UUID fileId,
        String targetKey,
        String capsuleKind,
        String summary,
        String text,
        String provider,
        String semanticModel,
        String promptVersion,
        String summarizerInputHash,
        String outputHash,
        boolean dataEgress,
        double confidence,
        UUID scanRunId,
        Map<String, Object> evidence) {

    public CodeCapsuleProjectionRecord {
        evidence = evidence == null ? Map.of() : Map.copyOf(evidence);
    }
}
