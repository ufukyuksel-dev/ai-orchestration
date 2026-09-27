package com.mbworldwideapps.aiorchestration.modules.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class McpClientContextTest {

    @Test
    void shouldMatchTrustedClientIdsCaseInsensitively() {
        McpClientContext context = new McpClientContext("PROJECT_A", "Codex", "mcp_abcd", List.of());

        assertThat(context.isTrusted(List.of("self-pipeline", "codex"))).isTrue();
    }

    @Test
    void shouldRejectUnknownTrustedClient() {
        McpClientContext context = new McpClientContext("PROJECT_A", "cursor", "mcp_abcd", List.of());

        assertThat(context.isTrusted(List.of("self-pipeline", "codex"))).isFalse();
    }
}
