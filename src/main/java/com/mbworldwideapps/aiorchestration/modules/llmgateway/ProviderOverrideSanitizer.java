package com.mbworldwideapps.aiorchestration.modules.llmgateway;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import com.mbworldwideapps.aiorchestration.modules.mcp.server.McpAccessException;
import com.mbworldwideapps.aiorchestration.modules.mcp.server.McpClientContext;
import org.springframework.stereotype.Component;

@Component
public class ProviderOverrideSanitizer {

    private static final Set<String> KNOWN_PROVIDERS = Set.of(
            "qwen", "local-qwen", "claude", "codex", "ide-bridge", "claude-stub");

    public Optional<String> sanitize(String override) {
        if (override == null || override.isBlank()) {
            return Optional.empty();
        }
        String normalized = override.trim().toLowerCase(Locale.ROOT);
        if (!KNOWN_PROVIDERS.contains(normalized)) {
            throw new IllegalArgumentException("Unknown provider override: " + override
                    + ". Allowed providers: " + KNOWN_PROVIDERS);
        }
        return Optional.of("qwen".equals(normalized) ? "local-qwen" : normalized);
    }

    public Optional<String> sanitize(String override, McpClientContext context) {
        Optional<String> provider = sanitize(override);
        if (provider.isEmpty()) {
            return provider;
        }
        String requiredScope = "provider." + provider.get();
        if (context == null || (!context.hasScope(requiredScope) && !context.hasScope("provider.*"))) {
            throw new McpAccessException("Provider override not allowed: " + provider.get()
                    + " (missing scope " + requiredScope + ")");
        }
        return provider;
    }
}
