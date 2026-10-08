package com.mbworldwideapps.aiorchestration.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Loopback agents (Claude Code, Codex, Copilot) get every scope by default, so all of them have the same, unrestricted
 * capability surface. Remote clients authenticate with bearer API keys whose levels are set in McpApiKeyService.
 */
@ConfigurationProperties(prefix = "ai-orchestration.local-trust")
public record LocalTrustProperties(
        boolean enabled,
        String defaultProjectKey,
        String defaultClientId,
        List<String> localScopes) {

    public static final List<String> DEFAULT_LOCAL_SCOPES = List.of(
            "memory.read",
            "memory.write",
            "memory.delete",
            "memory.pending",
            "memory.human_confirmed_approve",
            "memory.approve",
            "memory.admin",
            "memory.auto_active_write",
            "rules.read",
            "rules.write",
            "rules.confirm",
            "rules.global.confirm",
            "knowledge.read",
            "codebase.read",
            "context.graph.read",
            "context.semantic.read",
            "knowledge.ingest",
            "scanner.scan",
            "scanner.insights.write-memory",
            "transcript.write",
            "provider.*");

    public LocalTrustProperties {
        if (defaultProjectKey == null || defaultProjectKey.isBlank()) {
            defaultProjectKey = "AI_ORCHESTRATION";
        } else {
            defaultProjectKey = defaultProjectKey.trim();
        }
        if (defaultClientId == null || defaultClientId.isBlank()) {
            defaultClientId = "local-client";
        } else {
            defaultClientId = defaultClientId.trim();
        }
        if (localScopes == null || localScopes.isEmpty()) {
            localScopes = DEFAULT_LOCAL_SCOPES;
        } else {
            localScopes = localScopes.stream()
                    .filter(scope -> scope != null && !scope.isBlank())
                    .map(String::trim)
                    .distinct()
                    .toList();
            if (localScopes.isEmpty()) {
                localScopes = DEFAULT_LOCAL_SCOPES;
            }
        }
    }

    public List<String> effectiveLocalScopes() {
        return localScopes;
    }

    public static LocalTrustProperties disabled() {
        return new LocalTrustProperties(false, "AI_ORCHESTRATION", "local-client", DEFAULT_LOCAL_SCOPES);
    }
}
