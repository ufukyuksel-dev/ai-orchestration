package com.mbworldwideapps.aiorchestration.modules.mcp.server;

import java.util.UUID;

public record MemoryDeleteResponse(UUID memoryId, String mode, String status, String actor) {
}
