package com.mbworldwideapps.aiorchestration.modules.scanner;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record CodeScanRunRecord(
        UUID id,
        String projectKey,
        String rootPath,
        String status,
        Instant startedAt,
        Instant completedAt,
        String provider,
        String semanticModel,
        boolean dataEgress,
        int filesDiscovered,
        int filesScanned,
        int filesSkipped,
        int filesRejected,
        int candidatesCreated,
        Map<String, Object> metadata) {

    public CodeScanRunRecord(UUID id, String projectKey, String rootPath, String status, Instant startedAt,
            Instant completedAt, Map<String, Object> metadata) {
        this(id, projectKey, rootPath, status, startedAt, completedAt, "", "", false, 0, 0, 0, 0, 0, metadata);
    }

    public CodeScanRunRecord {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
