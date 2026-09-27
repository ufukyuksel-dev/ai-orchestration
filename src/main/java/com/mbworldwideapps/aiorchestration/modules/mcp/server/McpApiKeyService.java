package com.mbworldwideapps.aiorchestration.modules.mcp.server;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;

@Service
public class McpApiKeyService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final List<String> READ_ONLY_SCOPES = List.of("memory.read", "knowledge.read", "codebase.read",
            "rules.read", "context.graph.read", "context.semantic.read", "transcript.write", "provider.local-qwen");
    private static final List<String> WRITE_SCOPES = List.of("memory.read", "knowledge.read", "codebase.read",
            "context.graph.read", "context.semantic.read", "transcript.write", "memory.write", "memory.pending",
            "memory.human_confirmed_approve", "memory.delete", "rules.read", "rules.write", "rules.confirm",
            "rules.global.confirm", "knowledge.ingest", "scanner.scan",
            "scanner.insights.write-memory", "provider.local-qwen", "provider.claude", "provider.codex");
    private static final List<String> TRUSTED_WRITE_SCOPES = List.of("memory.read", "knowledge.read",
            "codebase.read", "context.graph.read", "context.semantic.read", "transcript.write", "memory.write",
            "memory.pending", "memory.human_confirmed_approve", "memory.delete", "knowledge.ingest",
            "rules.read", "rules.write", "rules.confirm", "rules.global.confirm", "scanner.scan",
            "scanner.insights.write-memory", "provider.local-qwen", "provider.claude", "provider.codex",
            "memory.auto_active_write");
    private static final List<String> ADMIN_SCOPES = List.of("memory.read", "knowledge.read", "codebase.read",
            "context.graph.read", "context.semantic.read", "transcript.write", "memory.write", "memory.pending",
            "memory.delete", "knowledge.ingest", "memory.approve", "memory.admin", "scanner.scan",
            "scanner.insights.write-memory", "rules.read", "rules.write", "rules.confirm",
            "rules.global.confirm", "provider.*");

    private final McpApiKeyRepository repository;

    public McpApiKeyService(McpApiKeyRepository repository) {
        this.repository = repository;
    }

    public GeneratedApiKey generateReadOnlyKey(String projectKey, String clientId) {
        return generate(projectKey, clientId, READ_ONLY_SCOPES);
    }

    public GeneratedApiKey generateWriteKey(String projectKey, String clientId) {
        return generate(projectKey, clientId, WRITE_SCOPES);
    }

    public GeneratedApiKey generateTrustedWriteKey(String projectKey, String clientId) {
        return generate(projectKey, clientId, TRUSTED_WRITE_SCOPES);
    }

    public GeneratedApiKey generateAdminKey(String projectKey, String clientId) {
        return generate(projectKey, clientId, ADMIN_SCOPES);
    }

    private GeneratedApiKey generate(String projectKey, String clientId, List<String> scopes) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String apiKey = "mcp_" + HexFormat.of().formatHex(bytes);
        McpApiKey saved = repository.save(new McpApiKey(
                UUID.randomUUID(),
                projectKey,
                clientId,
                McpAuditLogger.sha256Hex(apiKey),
                apiKey.substring(0, 8),
                normalizeScopes(scopes),
                Instant.now(),
                null,
                null));
        return new GeneratedApiKey(apiKey, saved);
    }

    private static List<String> normalizeScopes(List<String> scopes) {
        if (scopes == null || scopes.isEmpty()) {
            return READ_ONLY_SCOPES;
        }
        return scopes.stream()
                .filter(scope -> scope != null && !scope.isBlank())
                .map(String::trim)
                .collect(java.util.stream.Collectors.collectingAndThen(
                        java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new),
                        List::copyOf));
    }

    public record GeneratedApiKey(String apiKey, McpApiKey record) {
    }
}
