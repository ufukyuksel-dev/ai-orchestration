package com.mbworldwideapps.aiorchestration.modules.scanner;

import java.util.Map;
import java.util.UUID;

public record CodeDiagnosticRecord(
        UUID id,
        String projectKey,
        UUID scanRunId,
        String severity,
        String code,
        String message,
        String filePath,
        UUID symbolId,
        Map<String, Object> metadata) {
}
