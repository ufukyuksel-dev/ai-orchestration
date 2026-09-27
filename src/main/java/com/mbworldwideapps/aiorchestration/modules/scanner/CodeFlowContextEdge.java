package com.mbworldwideapps.aiorchestration.modules.scanner;

import java.util.UUID;

public record CodeFlowContextEdge(
        UUID edgeId,
        UUID sourceSymbolId,
        UUID targetSymbolId,
        String targetRef,
        String edgeType,
        String resolution,
        double confidence) {
}
