package com.mbworldwideapps.aiorchestration.modules.scanner;

import java.util.Map;
import java.util.UUID;

public record CodeEdgeRecord(
        UUID id,
        String projectKey,
        UUID sourceSymbolId,
        UUID targetSymbolId,
        String targetRef,
        String edgeType,
        String resolution,
        double confidence,
        UUID scanRunId,
        Map<String, Object> evidence) {

    public CodeEdgeRecord {
        evidence = evidence == null ? Map.of() : Map.copyOf(evidence);
    }
}
