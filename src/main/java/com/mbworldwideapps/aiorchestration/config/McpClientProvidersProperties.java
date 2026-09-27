package com.mbworldwideapps.aiorchestration.config;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ai-orchestration.mcp")
public record McpClientProvidersProperties(
        Map<String, String> clientProviders) {

    public McpClientProvidersProperties {
        if (clientProviders == null || clientProviders.isEmpty()) {
            clientProviders = Map.of();
        } else {
            Map<String, String> normalized = new LinkedHashMap<>();
            clientProviders.forEach((clientId, provider) -> {
                if (clientId != null && !clientId.isBlank()) {
                    normalized.put(clientId.trim().toLowerCase(Locale.ROOT),
                            MemoryWriteGateProperties.canonicalProvider(provider, false,
                                    "mcp.client-providers." + clientId.trim()));
                }
            });
            clientProviders = Map.copyOf(normalized);
        }
    }

    public String providerFor(String clientId) {
        if (clientId == null || clientId.isBlank()) {
            return null;
        }
        return clientProviders.get(clientId.trim().toLowerCase(Locale.ROOT));
    }
}
