package com.mbworldwideapps.aiorchestration.modules.mcp.server;

import java.util.Collection;
import java.util.List;
import java.util.Locale;

public record McpClientContext(
        String projectKey,
        String clientId,
        String keyPrefix,
        List<String> scopes,
        String sessionScopeHash) {

    public static final String SESSION_SCOPE_UNAVAILABLE = "SESSION_SCOPE_UNAVAILABLE";

    public McpClientContext {
        scopes = scopes == null ? List.of() : List.copyOf(scopes);
        sessionScopeHash = sessionScopeHash == null || sessionScopeHash.isBlank()
                ? McpAuditLogger.sha256Hex((clientId == null ? "unknown" : clientId) + ":legacy")
                : sessionScopeHash;
    }

    public McpClientContext(String projectKey, String clientId, String keyPrefix, List<String> scopes) {
        this(projectKey, clientId, keyPrefix, scopes, null);
    }

    public boolean hasScope(String scope) {
        if (scopes.contains(scope)) {
            return true;
        }
        int dot = scope == null ? -1 : scope.lastIndexOf('.');
        return dot > 0 && scopes.contains(scope.substring(0, dot) + ".*");
    }

    public boolean hasSessionScope() {
        return sessionScopeHash != null && sessionScopeHash.matches("[0-9a-f]{64}");
    }

    public boolean isTrusted(Collection<String> trustedClientIds) {
        if (clientId == null || clientId.isBlank() || trustedClientIds == null || trustedClientIds.isEmpty()) {
            return false;
        }
        String normalizedClientId = clientId.trim().toLowerCase(Locale.ROOT);
        return trustedClientIds.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .anyMatch(normalizedClientId::equals);
    }
}
