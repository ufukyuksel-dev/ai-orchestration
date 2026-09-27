package com.mbworldwideapps.aiorchestration.modules.mcp.server;

import jakarta.validation.constraints.NotBlank;

public record McpKeyGenerateRequest(
        @NotBlank String projectKey,
        @NotBlank String clientId,
        @NotBlank String scope) {
}
