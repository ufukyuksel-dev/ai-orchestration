package com.mbworldwideapps.aiorchestration.modules.scanner;

import java.util.List;
import java.util.UUID;

public record ScanCodebaseResponse(
        String rootPath,
        String projectKey,
        int filesDiscovered,
        int filesScanned,
        int filesSkipped,
        int filesRejected,
        int candidatesCreated,
        double unchangedSkipRate,
        long durationMs,
        List<UUID> memoryIds,
        UUID scanRunId) {

    public ScanCodebaseResponse(String rootPath, String projectKey, int filesDiscovered, int filesScanned,
            int filesSkipped, int filesRejected, int candidatesCreated, double unchangedSkipRate, long durationMs,
            List<UUID> memoryIds) {
        this(rootPath, projectKey, filesDiscovered, filesScanned, filesSkipped, filesRejected, candidatesCreated,
                unchangedSkipRate, durationMs, memoryIds, null);
    }
}
