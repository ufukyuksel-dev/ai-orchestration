package com.mbworldwideapps.aiorchestration.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

@ConfigurationProperties(prefix = "ai-orchestration.local-trust")
public record LocalTrustProperties(
        boolean enabled,
        String defaultProjectKey,
        String defaultClientId,
        List<String> localScopes,
        boolean autoActiveWrite) {

    private static final String AUTO_ACTIVE_WRITE_SCOPE = "memory.auto_active_write";

    public static final List<String> DEFAULT_LOCAL_SCOPES = List.of(
            "memory.read",
            "memory.write",
            "memory.delete",
            "memory.pending",
            "memory.human_confirmed_approve",
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
            "transcript.write",
            "provider.local-qwen",
            "provider.claude",
            "provider.codex");

    public LocalTrustProperties(boolean enabled, String defaultProjectKey, String defaultClientId,
            List<String> localScopes) {
        this(enabled, defaultProjectKey, defaultClientId, localScopes, false);
    }

    @ConstructorBinding
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
                    .filter(scope -> autoActiveWrite || !AUTO_ACTIVE_WRITE_SCOPE.equals(scope))
                    .distinct()
                    .toList();
            if (localScopes.isEmpty()) {
                localScopes = DEFAULT_LOCAL_SCOPES;
            }
        }
    }

    public List<String> effectiveLocalScopes() {
        if (!autoActiveWrite || localScopes.contains(AUTO_ACTIVE_WRITE_SCOPE)) {
            return localScopes;
        }
        return java.util.stream.Stream.concat(localScopes.stream(), java.util.stream.Stream.of(AUTO_ACTIVE_WRITE_SCOPE))
                .distinct()
                .toList();
    }

    public static LocalTrustProperties disabled() {
        return new LocalTrustProperties(false, "AI_ORCHESTRATION", "local-client", DEFAULT_LOCAL_SCOPES, false);
    }
}
