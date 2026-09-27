package com.mbworldwideapps.aiorchestration.modules.scanner;

import java.util.Map;
import java.util.UUID;

public record CodeCapsuleLinkTargetRecord(
        UUID id,
        String projectKey,
        UUID symbolId,
        UUID fileId,
        String targetKey,
        String capsuleKind,
        String outputHash,
        String provider,
        String semanticModel,
        String promptVersion,
        UUID scanRunId,
        Map<String, Object> evidence) {

    public CodeCapsuleLinkTargetRecord {
        evidence = evidence == null ? Map.of() : Map.copyOf(evidence);
    }
}
