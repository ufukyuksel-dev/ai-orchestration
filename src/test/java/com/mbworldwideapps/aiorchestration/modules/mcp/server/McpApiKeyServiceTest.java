package com.mbworldwideapps.aiorchestration.modules.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

class McpApiKeyServiceTest {

    private final InMemoryApiKeyRepository repository = new InMemoryApiKeyRepository();
    private final McpApiKeyService service = new McpApiKeyService(repository);

    @Test
    void shouldGenerateReadOnlyKeyWithDefaultScopes() {
        McpApiKeyService.GeneratedApiKey generated = service.generateReadOnlyKey("PROJECT_A", "readonly-client");

        assertThat(generated.apiKey()).startsWith("mcp_");
        assertThat(generated.record().apiKeyHash()).isEqualTo(McpAuditLogger.sha256Hex(generated.apiKey()));
        assertThat(generated.record().scopes())
                .containsExactly("memory.read", "knowledge.read", "codebase.read", "rules.read", "context.graph.read",
                        "context.semantic.read", "transcript.write", "provider.local-qwen");
    }

    @Test
    void shouldGenerateWriteKeyWithWriteScopes() {
        McpApiKeyService.GeneratedApiKey generated = service.generateWriteKey("PROJECT_A", "writer-client");

        assertThat(generated.record().scopes())
                .containsExactly("memory.read", "knowledge.read", "codebase.read", "context.graph.read",
                        "context.semantic.read", "transcript.write", "memory.write", "memory.pending",
                        "memory.human_confirmed_approve", "memory.delete", "rules.read", "rules.write",
                        "rules.confirm", "rules.global.confirm", "knowledge.ingest", "scanner.scan",
                        "scanner.insights.write-memory", "provider.local-qwen", "provider.claude",
                        "provider.codex");
    }

    @Test
    void shouldGenerateTrustedWriteKeyWithAutoActiveScope() {
        McpApiKeyService.GeneratedApiKey generated = service.generateTrustedWriteKey("PROJECT_A", "trusted-client");

        assertThat(generated.record().scopes())
                .containsExactly("memory.read", "knowledge.read", "codebase.read", "context.graph.read",
                        "context.semantic.read", "transcript.write", "memory.write", "memory.pending",
                        "memory.human_confirmed_approve", "memory.delete", "knowledge.ingest",
                        "rules.read", "rules.write", "rules.confirm", "rules.global.confirm", "scanner.scan",
                        "scanner.insights.write-memory", "provider.local-qwen", "provider.claude",
                        "provider.codex", "memory.auto_active_write");
    }

    @Test
    void shouldGenerateAdminKeyWithAdminScopes() {
        McpApiKeyService.GeneratedApiKey generated = service.generateAdminKey("PROJECT_A", "admin-client");

        assertThat(generated.record().scopes())
                .containsExactly("memory.read", "knowledge.read", "codebase.read", "context.graph.read",
                        "context.semantic.read", "transcript.write", "memory.write", "memory.pending",
                        "memory.delete", "knowledge.ingest", "memory.approve", "memory.admin", "scanner.scan",
                        "scanner.insights.write-memory", "rules.read", "rules.write", "rules.confirm",
                        "rules.global.confirm", "provider.*");
    }

    private static final class InMemoryApiKeyRepository implements McpApiKeyRepository {
        private final Map<String, McpApiKey> keys = new HashMap<>();

        @Override
        public Optional<McpApiKey> findActiveByHash(String apiKeyHash) {
            return Optional.ofNullable(keys.get(apiKeyHash));
        }

        @Override
        public void updateLastUsed(String apiKeyHash) {
        }

        @Override
        public McpApiKey save(McpApiKey apiKey) {
            keys.put(apiKey.apiKeyHash(), apiKey);
            return apiKey;
        }
    }
}
