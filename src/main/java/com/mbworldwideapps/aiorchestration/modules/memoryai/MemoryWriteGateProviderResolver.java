package com.mbworldwideapps.aiorchestration.modules.memoryai;

import com.mbworldwideapps.aiorchestration.config.McpClientProvidersProperties;
import com.mbworldwideapps.aiorchestration.config.MemoryWriteGateProperties;
import com.mbworldwideapps.aiorchestration.modules.llmgateway.ProviderOverrideSanitizer;
import com.mbworldwideapps.aiorchestration.modules.mcp.server.McpClientContext;
import org.springframework.stereotype.Component;

@Component
public class MemoryWriteGateProviderResolver {

    private final MemoryWriteGateProperties properties;
    private final McpClientProvidersProperties clientProviders;
    private final ProviderOverrideSanitizer providerOverrideSanitizer;

    public MemoryWriteGateProviderResolver(MemoryWriteGateProperties properties,
            McpClientProvidersProperties clientProviders, ProviderOverrideSanitizer providerOverrideSanitizer) {
        this.properties = properties;
        this.clientProviders = clientProviders;
        this.providerOverrideSanitizer = providerOverrideSanitizer;
    }

    public String resolve(McpClientContext context, String providerOverride) {
        return resolve(properties, clientProviders, context, providerOverride, providerOverrideSanitizer);
    }

    public static String resolve(MemoryWriteGateProperties properties, McpClientProvidersProperties clientProviders,
            McpClientContext context, String providerOverride, ProviderOverrideSanitizer providerOverrideSanitizer) {
        return providerOverrideSanitizer.sanitize(providerOverride, context)
                .orElseGet(() -> resolveConfigured(properties, clientProviders, context));
    }

    private static String resolveConfigured(MemoryWriteGateProperties properties,
            McpClientProvidersProperties clientProviders, McpClientContext context) {
        if (!properties.sessionProviderMode()) {
            return properties.provider();
        }
        String sessionProvider = context == null ? null : clientProviders.providerFor(context.clientId());
        return sessionProvider == null ? properties.sessionFallbackProvider() : sessionProvider;
    }
}
