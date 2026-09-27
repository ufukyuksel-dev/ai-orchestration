package com.mbworldwideapps.aiorchestration.modules.mcp.server;

import java.util.List;

public record McpKeyListResponse(List<McpKeySummary> keys, int count) {
}
