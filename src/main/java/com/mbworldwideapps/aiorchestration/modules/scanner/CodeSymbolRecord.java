package com.mbworldwideapps.aiorchestration.modules.scanner;

import java.util.Map;
import java.util.UUID;

public record CodeSymbolRecord(
        UUID id,
        String projectKey,
        UUID fileId,
        String symbolKind,
        String name,
        String fqn,
        String signature,
        String role,
        Integer startLine,
        Integer endLine,
        String contentHash,
        UUID scanRunId,
        Map<String, Object> metadata) {

    public CodeSymbolRecord {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
