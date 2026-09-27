package com.mbworldwideapps.aiorchestration.modules.scanner;

import java.util.UUID;

import com.mbworldwideapps.aiorchestration.modules.scanner.GraphProjectionKeys;

public record CodeEndpointAnchorCandidateRecord(
        UUID edgeId,
        String projectKey,
        UUID sourceSymbolId,
        String targetRef,
        String referenceKey,
        String sourceName,
        String sourceFqn,
        Integer startLine,
        Integer endLine,
        double confidence) {

    public CodeEndpointAnchorCandidateRecord(UUID edgeId, String projectKey, UUID sourceSymbolId, String targetRef,
            String sourceName, String sourceFqn, Integer startLine, Integer endLine, double confidence) {
        this(edgeId, projectKey, sourceSymbolId, targetRef,
                GraphProjectionKeys.codeReferenceKey(projectKey, "EXPOSES_ENDPOINT", targetRef),
                sourceName, sourceFqn, startLine, endLine, confidence);
    }
}
