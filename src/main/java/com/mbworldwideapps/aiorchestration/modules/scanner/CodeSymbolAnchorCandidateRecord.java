package com.mbworldwideapps.aiorchestration.modules.scanner;

import java.util.List;

public record CodeSymbolAnchorCandidateRecord(
        CodeSymbolRecord symbol,
        int incomingEdges,
        int outgoingEdges,
        int endpointEdges,
        List<String> annotations) {

    public CodeSymbolAnchorCandidateRecord {
        annotations = annotations == null ? List.of() : List.copyOf(annotations);
        if (incomingEdges < 0) {
            incomingEdges = 0;
        }
        if (outgoingEdges < 0) {
            outgoingEdges = 0;
        }
        if (endpointEdges < 0) {
            endpointEdges = 0;
        }
    }
}
