package com.mbworldwideapps.aiorchestration.modules.mcp.server;

import java.util.List;

public interface McpAccessLogRepository {

    void insert(McpAccessLogEntry entry);

    default List<McpAccessLogEntry> list() {
        return List.of();
    }
}
