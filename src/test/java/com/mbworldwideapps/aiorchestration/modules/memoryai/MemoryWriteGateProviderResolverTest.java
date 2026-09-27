package com.mbworldwideapps.aiorchestration.modules.memoryai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.mbworldwideapps.aiorchestration.config.McpClientProvidersProperties;
import com.mbworldwideapps.aiorchestration.config.MemoryWriteGateProperties;
import com.mbworldwideapps.aiorchestration.modules.llmgateway.ProviderOverrideSanitizer;
import com.mbworldwideapps.aiorchestration.modules.mcp.server.McpAccessException;
import com.mbworldwideapps.aiorchestration.modules.mcp.server.McpClientContext;
import org.junit.jupiter.api.Test;

class MemoryWriteGateProviderResolverTest {

    private final ProviderOverrideSanitizer sanitizer = new ProviderOverrideSanitizer();

    @Test
    void explicitProviderWinsOverSessionMapping() {
        String provider = resolve(
                properties("claude", null),
                providers("codex", "codex"),
                context("codex", "provider.codex"),
                null);

        assertThat(provider).isEqualTo("claude");
    }

    @Test
    void sessionProviderUsesKnownClientMapping() {
        String provider = resolve(
                properties("sessionProvider", "qwen"),
                providers("claude-code", "claude"),
                context("claude-code", "provider.claude"),
                null);

        assertThat(provider).isEqualTo("claude");
    }

    @Test
    void sessionProviderFallsBackForUnknownClient() {
        String provider = resolve(
                properties("sessionProvider", "qwen"),
                providers("claude-code", "claude"),
                context("unknown-client", "provider.local-qwen"),
                null);

        assertThat(provider).isEqualTo("local-qwen");
    }

    @Test
    void providerOverrideWinsWhenScopeAllowsIt() {
        String provider = resolve(
                properties("sessionProvider", "qwen"),
                providers("self-pipeline", "qwen"),
                context("self-pipeline", "provider.codex"),
                "codex");

        assertThat(provider).isEqualTo("codex");
    }

    @Test
    void providerOverrideRequiresProviderScope() {
        assertThatThrownBy(() -> resolve(
                properties("sessionProvider", "qwen"),
                providers("self-pipeline", "qwen"),
                context("self-pipeline", "provider.local-qwen"),
                "codex"))
                .isInstanceOf(McpAccessException.class)
                .hasMessageContaining("provider.codex");
    }

    private String resolve(MemoryWriteGateProperties properties, McpClientProvidersProperties providers,
            McpClientContext context, String override) {
        return MemoryWriteGateProviderResolver.resolve(properties, providers, context, override, sanitizer);
    }

    private static MemoryWriteGateProperties properties(String provider, String fallback) {
        return new MemoryWriteGateProperties(provider, fallback, 0.85, Duration.ofSeconds(3),
                Set.of("self-pipeline", "claude-code", "codex"));
    }

    private static McpClientProvidersProperties providers(String clientId, String provider) {
        return new McpClientProvidersProperties(Map.of(clientId, provider));
    }

    private static McpClientContext context(String clientId, String providerScope) {
        return new McpClientContext("PROJECT_A", clientId, "mcp_abcd",
                List.of("memory.write", providerScope));
    }
}
