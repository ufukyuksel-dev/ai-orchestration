package com.mbworldwideapps.aiorchestration.modules.scanner;

import java.util.UUID;

public record ScannerScanStartResponse(
        UUID scanRunId,
        String status,
        String rootPath,
        String projectKey,
        boolean force,
        String providerOverride,
        String semanticModel,
        boolean forceReindex,
        String message) {
}
