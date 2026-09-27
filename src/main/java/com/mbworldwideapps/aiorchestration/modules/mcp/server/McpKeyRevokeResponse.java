package com.mbworldwideapps.aiorchestration.modules.mcp.server;

import java.time.Instant;
import java.util.UUID;

public record McpKeyRevokeResponse(UUID id, boolean revoked, Instant revokedAt) {
}
