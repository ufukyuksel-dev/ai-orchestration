package com.mbworldwideapps.aiorchestration.modules.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mbworldwideapps.aiorchestration.core.policy.PolicyProperties;
import com.mbworldwideapps.aiorchestration.core.security.AdminTokenValidator;
import org.junit.jupiter.api.Test;

class McpAdminEndpointsIT {

    private final InMemoryApiKeyRepository repository = new InMemoryApiKeyRepository();
    private final McpApiKeyService apiKeyService = new McpApiKeyService(repository);
    private final McpAdminController controller = new McpAdminController(apiKeyService, repository,
            new AdminTokenValidator(new PolicyProperties(true, true, "test-admin-token", List.of())));

    @Test
    void shouldGenerateReadOnlyKey() {
        McpKeyGenerateResponse response = controller.generate("test-admin-token",
                new McpKeyGenerateRequest("PROJECT_A", "readonly-client", "read-only"));

        assertThat(response.apiKey()).startsWith("mcp_");
        assertThat(response.scopes()).containsExactly("memory.read", "knowledge.read", "codebase.read",
                "rules.read", "context.graph.read", "context.semantic.read", "transcript.write",
                "provider.local-qwen");
        assertThat(repository.findById(response.id())).isPresent();
    }

    @Test
    void shouldGenerateWriteKey() {
        McpKeyGenerateResponse response = controller.generate("test-admin-token",
                new McpKeyGenerateRequest("PROJECT_A", "write-client", "write"));

        assertThat(response.scopes()).containsExactly("memory.read", "knowledge.read", "codebase.read",
                "context.graph.read", "context.semantic.read", "transcript.write",
                "memory.write", "memory.pending", "memory.human_confirmed_approve", "memory.delete",
                "rules.read", "rules.write", "rules.confirm", "rules.global.confirm", "knowledge.ingest",
                "scanner.scan", "scanner.insights.write-memory", "provider.local-qwen",
                "provider.claude", "provider.codex");
    }

    @Test
    void shouldGenerateTrustedWriteKey() {
        McpKeyGenerateResponse response = controller.generate("test-admin-token",
                new McpKeyGenerateRequest("PROJECT_A", "trusted-client", "trusted-write"));

        assertThat(response.scopes()).containsExactly("memory.read", "knowledge.read", "codebase.read",
                "context.graph.read", "context.semantic.read", "transcript.write",
                "memory.write", "memory.pending", "memory.human_confirmed_approve", "memory.delete",
                "knowledge.ingest", "rules.read", "rules.write", "rules.confirm", "rules.global.confirm",
                "scanner.scan", "scanner.insights.write-memory", "provider.local-qwen",
                "provider.claude", "provider.codex", "memory.auto_active_write");
    }

    @Test
    void shouldGenerateAdminKey() {
        McpKeyGenerateResponse response = controller.generate("test-admin-token",
                new McpKeyGenerateRequest("PROJECT_A", "admin-client", "admin"));

        assertThat(response.scopes()).containsExactly("memory.read", "knowledge.read", "codebase.read",
                "context.graph.read", "context.semantic.read", "transcript.write",
                "memory.write", "memory.pending", "memory.delete", "knowledge.ingest", "memory.approve",
                "memory.admin", "scanner.scan", "scanner.insights.write-memory", "rules.read", "rules.write",
                "rules.confirm", "rules.global.confirm", "provider.*");
    }

    @Test
    void shouldRejectWithInvalidAdminToken() {
        assertThatThrownBy(() -> controller.generate("wrong-token",
                new McpKeyGenerateRequest("PROJECT_A", "client", "write")))
                .isInstanceOf(McpAccessException.class);
    }

    @Test
    void shouldRejectInvalidScope() {
        assertThatThrownBy(() -> controller.generate("test-admin-token",
                new McpKeyGenerateRequest("PROJECT_A", "client", "invalid")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scope must be");
    }

    @Test
    void shouldListActiveKeysWithoutPlaintext() throws Exception {
        McpKeyGenerateResponse generated = controller.generate("test-admin-token",
                new McpKeyGenerateRequest("PROJECT_A", "self-pipeline", "write"));

        McpKeyListResponse response = controller.list("test-admin-token", "PROJECT_A");
        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(response);

        assertThat(response.keys()).hasSize(1);
        assertThat(response.keys().getFirst().keyPrefix()).isEqualTo(generated.keyPrefix());
        assertThat(json).contains("keyPrefix").doesNotContain("apiKey").doesNotContain(generated.apiKey());
    }

    @Test
    void shouldRevokeKeyAndRejectSubsequentMcpAuth() throws Exception {
        McpKeyGenerateResponse generated = controller.generate("test-admin-token",
                new McpKeyGenerateRequest("PROJECT_A", "self-pipeline", "write"));

        McpKeyRevokeResponse response = controller.revoke("test-admin-token", generated.id(),
                new McpKeyRevokeRequest("rotation"));

        assertThat(response.revoked()).isTrue();
        assertThat(repository.findById(generated.id()).orElseThrow().revokedAt()).isNotNull();
        assertThat(repository.findActiveByHash(McpAuditLogger.sha256Hex(generated.apiKey()))).isEmpty();
    }

    @Test
    void shouldNotRevokeTwice() {
        McpKeyGenerateResponse generated = controller.generate("test-admin-token",
                new McpKeyGenerateRequest("PROJECT_A", "self-pipeline", "write"));
        controller.revoke("test-admin-token", generated.id(), new McpKeyRevokeRequest("rotation"));

        assertThatThrownBy(() -> controller.revoke("test-admin-token", generated.id(),
                new McpKeyRevokeRequest("again")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already revoked");
    }

    private static final class InMemoryApiKeyRepository implements McpApiKeyRepository {
        private final Map<UUID, McpApiKey> keysById = new HashMap<>();
        private final Map<String, UUID> idsByHash = new HashMap<>();

        @Override
        public Optional<McpApiKey> findActiveByHash(String apiKeyHash) {
            UUID id = idsByHash.get(apiKeyHash);
            if (id == null) {
                return Optional.empty();
            }
            return findById(id).filter(key -> key.revokedAt() == null);
        }

        @Override
        public void updateLastUsed(String apiKeyHash) {
            UUID id = idsByHash.get(apiKeyHash);
            if (id == null) {
                return;
            }
            McpApiKey key = keysById.get(id);
            keysById.put(id, copy(key, key.revokedAt(), Instant.now()));
        }

        @Override
        public McpApiKey save(McpApiKey apiKey) {
            keysById.put(apiKey.id(), apiKey);
            idsByHash.put(apiKey.apiKeyHash(), apiKey.id());
            return apiKey;
        }

        @Override
        public List<McpApiKey> listActive(String projectKey) {
            return keysById.values().stream()
                    .filter(key -> key.revokedAt() == null)
                    .filter(key -> projectKey == null || projectKey.equals(key.projectKey()))
                    .sorted(Comparator.comparing(McpApiKey::createdAt).reversed())
                    .limit(100)
                    .toList();
        }

        @Override
        public Optional<McpApiKey> findById(UUID id) {
            return Optional.ofNullable(keysById.get(id));
        }

        @Override
        public boolean revoke(UUID id, Instant revokedAt) {
            McpApiKey key = keysById.get(id);
            if (key == null || key.revokedAt() != null) {
                return false;
            }
            keysById.put(id, copy(key, revokedAt, key.lastUsedAt()));
            return true;
        }

        @Override
        public Optional<McpApiKey> findByPrefix(String keyPrefix) {
            return keysById.values().stream()
                    .filter(key -> key.revokedAt() == null)
                    .filter(key -> key.keyPrefix().equals(keyPrefix))
                    .findFirst();
        }

        private static McpApiKey copy(McpApiKey key, Instant revokedAt, Instant lastUsedAt) {
            return new McpApiKey(key.id(), key.projectKey(), key.clientId(), key.apiKeyHash(), key.keyPrefix(),
                    new ArrayList<>(key.scopes()), key.createdAt(), revokedAt, lastUsedAt);
        }
    }
}
