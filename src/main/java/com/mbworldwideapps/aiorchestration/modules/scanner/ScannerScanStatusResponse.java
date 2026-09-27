package com.mbworldwideapps.aiorchestration.modules.scanner;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record ScannerScanStatusResponse(
        UUID scanRunId,
        String status,
        String rootPath,
        String projectKey,
        String provider,
        String semanticModel,
        boolean dataEgress,
        Instant startedAt,
        Instant completedAt,
        int filesDiscovered,
        int filesScanned,
        int filesSkipped,
        int filesRejected,
        int candidatesCreated,
        boolean done,
        boolean successful,
        Map<String, Object> metadata) {

    public ScannerScanStatusResponse {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
