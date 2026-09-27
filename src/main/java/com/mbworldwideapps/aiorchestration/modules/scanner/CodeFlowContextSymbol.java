package com.mbworldwideapps.aiorchestration.modules.scanner;

import java.util.List;
import java.util.UUID;

public record CodeFlowContextSymbol(
        UUID symbolId,
        String symbolKind,
        String name,
        String fqn,
        String signature,
        String role,
        String filePath,
        Integer startLine,
        Integer endLine,
        List<String> annotations,
        String reason) {

    public CodeFlowContextSymbol {
        annotations = annotations == null ? List.of() : List.copyOf(annotations);
    }
}
