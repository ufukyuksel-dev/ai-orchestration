package com.mbworldwideapps.aiorchestration.modules.scanner;

import java.util.UUID;

public record CodeFlowSnippet(
        UUID symbolId,
        String title,
        String filePath,
        Integer startLine,
        Integer endLine,
        String text) {
}
