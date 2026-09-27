package com.mbworldwideapps.aiorchestration.modules.mcp.server;

import java.util.UUID;

public record ScannerProjectResolveResponse(
        String rootPath,
        String projectKey,
        String callerProjectKey,
        boolean localTrust,
        boolean bindingVerified,
        String repositoryFingerprint,
        UUID workspaceBindingId) {
}
