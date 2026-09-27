package com.mbworldwideapps.aiorchestration.modules.scanner;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ScannerFileState(
        String projectKey,
        String filePath,
        String contentHash,
        List<UUID> extractedMemoryIds,
        Instant lastScannedAt) {

    public ScannerFileState(String filePath, String contentHash, List<UUID> extractedMemoryIds,
            Instant lastScannedAt) {
        this("AI_ORCHESTRATION", filePath, contentHash, extractedMemoryIds, lastScannedAt);
    }
}
