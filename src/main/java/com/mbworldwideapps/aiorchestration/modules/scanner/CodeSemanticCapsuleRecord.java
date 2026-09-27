package com.mbworldwideapps.aiorchestration.modules.scanner;

import java.util.Map;
import java.util.UUID;

public record CodeSemanticCapsuleRecord(
        UUID id,
        String projectKey,
        UUID symbolId,
        UUID fileId,
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

    public CodeSemanticCapsuleRecord {
        evidence = evidence == null ? Map.of() : Map.copyOf(evidence);
    }

    /** Stable Scanner target identity across capsule re-generation runs. */
    public String targetKey() {
        if (symbolId != null) {
            return symbolId.toString();
        }
        return fileId == null ? "" : fileId.toString();
    }
}
