package com.mbworldwideapps.aiorchestration.modules.mcp.server;

import java.util.List;

import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryItem;

public record MemoryReviewPendingResponse(List<MemoryItem> items, int count, int limit, int offset) {
}
