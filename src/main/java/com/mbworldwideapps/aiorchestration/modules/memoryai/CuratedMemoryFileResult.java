package com.mbworldwideapps.aiorchestration.modules.memoryai;

import java.util.UUID;

public record CuratedMemoryFileResult(
        String path,
        String id,
        String scope,
        String action,
        String reason,
        UUID memoryId) {
}
