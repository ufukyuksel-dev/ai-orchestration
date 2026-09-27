package com.mbworldwideapps.aiorchestration.modules.memoryai;

public record MemorySignalDetection(
        MemorySignalType signalType,
        String matchedPhrase,
        double signalStrength) {
}
