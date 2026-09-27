package com.mbworldwideapps.aiorchestration.modules.llmgateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import com.mbworldwideapps.aiorchestration.modules.mcp.server.McpAccessException;
import com.mbworldwideapps.aiorchestration.modules.mcp.server.McpClientContext;
import org.junit.jupiter.api.Test;

class ProviderOverrideSanitizerTest {

    private final ProviderOverrideSanitizer sanitizer = new ProviderOverrideSanitizer();

    @Test
    void shouldNormalizeKnownProvider() {
        assertThat(sanitizer.sanitize(" CODEX ")).contains("codex");
    }

    @Test
    void shouldNormalizeQwenAliasToLocalQwenProvider() {
        assertThat(sanitizer.sanitize("qwen")).contains("local-qwen");
    }

    @Test
    void shouldReturnEmptyForBlankOverride() {
        assertThat(sanitizer.sanitize(" ")).isEmpty();
    }

    @Test
    void shouldRejectUnknownProvider() {
        assertThatThrownBy(() -> sanitizer.sanitize("gpt-unknown"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown provider override");
    }

    @Test
    void shouldAllowMcpClientWithProviderScope() {
        McpClientContext context = context("provider.codex");

        assertThat(sanitizer.sanitize("codex", context)).contains("codex");
    }

    @Test
    void shouldAllowMcpClientWithWildcardProviderScope() {
        McpClientContext context = context("provider.*");

        assertThat(sanitizer.sanitize("claude", context)).contains("claude");
    }

    @Test
    void shouldRejectMcpClientWithoutProviderScope() {
        McpClientContext context = context("provider.local-qwen");

        assertThatThrownBy(() -> sanitizer.sanitize("codex", context))
                .isInstanceOf(McpAccessException.class)
                .hasMessageContaining("missing scope provider.codex");
    }

    private static McpClientContext context(String providerScope) {
        return new McpClientContext("PROJECT_A", "self-pipeline", "mcp_abcd",
                List.of("knowledge.read", providerScope));
    }
}
