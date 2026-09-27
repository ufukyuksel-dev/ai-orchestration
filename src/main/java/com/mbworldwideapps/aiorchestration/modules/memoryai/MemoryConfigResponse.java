package com.mbworldwideapps.aiorchestration.modules.memoryai;

public record MemoryConfigResponse(
        String episodicCollectionName,
        int contextTokenCap,
        double defaultConfidence) {
}
