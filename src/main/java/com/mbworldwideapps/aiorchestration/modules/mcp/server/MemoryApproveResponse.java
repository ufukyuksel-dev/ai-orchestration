package com.mbworldwideapps.aiorchestration.modules.mcp.server;

import java.util.UUID;

public record MemoryApproveResponse(UUID memoryId, String status, String actor) {
}
