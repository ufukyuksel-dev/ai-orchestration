package com.mbworldwideapps.aiorchestration.modules.memoryai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

class MemoryScopeResolverTest {

    @Test
    void shouldUseAgentSelectedGlobalScope() {
        MemoryScopeResolution resolution = MemoryScopeResolver.resolveExternal("global", MemoryType.RULE,
                "Architecture", "Reusable architecture rule", List.of("architecture"), "PROJECT_A");

        assertThat(resolution.scope()).isEqualTo(MemoryScope.GLOBAL);
        assertThat(resolution.mode()).isEqualTo("agent_selected");
    }

    @Test
    void shouldUseAgentSelectedProjectScope() {
        MemoryScopeResolution resolution = MemoryScopeResolver.resolveExternal("project", MemoryType.RULE,
                "Architecture", "Project-specific architecture rule", List.of("architecture"), "PROJECT_A");

        assertThat(resolution.scope()).isEqualTo(MemoryScope.PROJECT);
        assertThat(resolution.mode()).isEqualTo("agent_selected");
    }

    @Test
    void shouldInferGlobalForReusableEngineeringRule() {
        MemoryScopeResolution resolution = MemoryScopeResolver.resolveExternal(null, MemoryType.RULE,
                "Hexagonal rule",
                "Architecture rule: write ArchUnit tests for hexagonal boundaries and keep domain clean.",
                List.of("architecture", "archunit"), "PROJECT_A");

        assertThat(resolution.scope()).isEqualTo(MemoryScope.GLOBAL);
        assertThat(resolution.mode()).isEqualTo("server_classified");
    }

    @Test
    void shouldInferGlobalForTurkishEngineeringRule() {
        MemoryScopeResolution resolution = MemoryScopeResolver.resolveExternal(null, MemoryType.RULE,
                "Temiz mimari kuralı",
                "Mimari katman sınırları ArchTest ile korunmalı ve domain temiz kalmalı.",
                List.of("mimari", "archtest"), "PROJECT_A");

        assertThat(resolution.scope()).isEqualTo(MemoryScope.GLOBAL);
        assertThat(resolution.mode()).isEqualTo("server_classified");
    }

    @Test
    void shouldInferProjectWhenProjectKeyIsMentioned() {
        MemoryScopeResolution resolution = MemoryScopeResolver.resolveExternal(null, MemoryType.RULE,
                "Local rule", "AI_ORCHESTRATION keeps MCP server tools under modules/mcp/server.",
                List.of("mcp"), "AI_ORCHESTRATION");

        assertThat(resolution.scope()).isEqualTo(MemoryScope.PROJECT);
        assertThat(resolution.reason()).isEqualTo("mentions project key");
    }

    @Test
    void shouldInferProjectForRepoSignals() {
        MemoryScopeResolution resolution = MemoryScopeResolver.resolveExternal(null, MemoryType.RULE,
                "Repo rule", "Files under src/main/java/modules/mcp/server use MCP annotations.",
                List.of("src"), "PROJECT_A");

        assertThat(resolution.scope()).isEqualTo(MemoryScope.PROJECT);
        assertThat(resolution.reason()).isEqualTo("repo-specific signal");
    }

    @Test
    void shouldDefaultToProjectWhenNoGlobalSignalExists() {
        MemoryScopeResolution resolution = MemoryScopeResolver.resolveExternal(null, MemoryType.CORRECTION,
                "Naming preference", "Use short method names here.", List.of("style"), "PROJECT_A");

        assertThat(resolution.scope()).isEqualTo(MemoryScope.PROJECT);
        assertThat(resolution.mode()).isEqualTo("server_default");
    }

    @Test
    void shouldKeepPreferenceWithEngineeringKeywordProjectScopedByDefault() {
        MemoryScopeResolution resolution = MemoryScopeResolver.resolveExternal(null, MemoryType.PREFERENCE,
                "Architecture preference", "I prefer architecture notes to be concise.", List.of("architecture"),
                "PROJECT_A");

        assertThat(resolution.scope()).isEqualTo(MemoryScope.PROJECT);
        assertThat(resolution.mode()).isEqualTo("server_default");
    }

    @Test
    void shouldKeepDiscoveryProjectScoped() {
        MemoryScopeResolution resolution = MemoryScopeResolver.resolveExternal(null, MemoryType.DISCOVERY,
                "Resolver behavior", "A reusable code behavior discovered during research.",
                List.of("navigation"), "PROJECT_A");

        assertThat(resolution.scope()).isEqualTo(MemoryScope.PROJECT);
        assertThat(resolution.reason()).isEqualTo("discovery is project-scoped");
    }

    @Test
    void shouldRejectExplicitGlobalDiscoveryScope() {
        assertThatThrownBy(() -> MemoryScopeResolver.resolveExternal("global", MemoryType.DISCOVERY,
                "Resolver behavior", "A reusable code behavior discovered during research.",
                List.of("navigation"), "PROJECT_A"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("discovery scope must be project");
    }

    @Test
    void shouldRejectUnsupportedExternalScope() {
        assertThatThrownBy(() -> MemoryScopeResolver.resolveExternal("episodic", MemoryType.RULE,
                "summary", "text", List.of(), "PROJECT_A"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("global or project");
    }
}
