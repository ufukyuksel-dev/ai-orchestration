package com.mbworldwideapps.aiorchestration.modules.scanner;

import java.util.Map;
import java.util.UUID;

public record CodeFileRecord(
        UUID id,
        String projectKey,
        String filePath,
        String contentHash,
        String language,
        UUID scanRunId,
        Map<String, Object> metadata) {

    public CodeFileRecord {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
