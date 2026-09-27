package com.mbworldwideapps.aiorchestration.modules.mcp.server;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface McpApiKeyRepository {

    Optional<McpApiKey> findActiveByHash(String apiKeyHash);

    void updateLastUsed(String apiKeyHash);

    McpApiKey save(McpApiKey apiKey);

    default List<McpApiKey> listActive(String projectKey) {
        throw new UnsupportedOperationException("listActive is not implemented");
    }

    default Optional<McpApiKey> findById(UUID id) {
        throw new UnsupportedOperationException("findById is not implemented");
    }

    default boolean revoke(UUID id, Instant revokedAt) {
        throw new UnsupportedOperationException("revoke is not implemented");
    }

    default Optional<McpApiKey> findByPrefix(String keyPrefix) {
        throw new UnsupportedOperationException("findByPrefix is not implemented");
    }
}
