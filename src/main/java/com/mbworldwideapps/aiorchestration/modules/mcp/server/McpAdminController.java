package com.mbworldwideapps.aiorchestration.modules.mcp.server;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

import com.mbworldwideapps.aiorchestration.core.security.AdminTokenValidator;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/mcp/api-keys")
public class McpAdminController {

    private static final String ADMIN_TOKEN_HEADER = "X-Admin-Token";

    private final McpApiKeyService apiKeyService;
    private final McpApiKeyRepository apiKeyRepository;
    private final AdminTokenValidator adminTokenValidator;

    public McpAdminController(McpApiKeyService apiKeyService, McpApiKeyRepository apiKeyRepository,
            AdminTokenValidator adminTokenValidator) {
        this.apiKeyService = apiKeyService;
        this.apiKeyRepository = apiKeyRepository;
        this.adminTokenValidator = adminTokenValidator;
    }

    @PostMapping("/generate")
    public McpKeyGenerateResponse generate(@RequestHeader(ADMIN_TOKEN_HEADER) String adminToken,
            @Valid @RequestBody McpKeyGenerateRequest request) {
        adminTokenValidator.require(adminToken);
        McpApiKeyService.GeneratedApiKey generated = switch (normalizeScope(request.scope())) {
            case "read-only" -> apiKeyService.generateReadOnlyKey(request.projectKey(), request.clientId());
            case "write" -> apiKeyService.generateWriteKey(request.projectKey(), request.clientId());
            case "trusted-write" -> apiKeyService.generateTrustedWriteKey(request.projectKey(), request.clientId());
            case "admin" -> apiKeyService.generateAdminKey(request.projectKey(), request.clientId());
            default -> throw new IllegalArgumentException("scope must be: read-only | write | trusted-write | admin");
        };
        McpApiKey key = generated.record();
        return new McpKeyGenerateResponse(key.id(), key.projectKey(), key.clientId(), key.keyPrefix(),
                generated.apiKey(), key.scopes(), key.createdAt());
    }

    @GetMapping
    public McpKeyListResponse list(@RequestHeader(ADMIN_TOKEN_HEADER) String adminToken,
            @RequestParam(required = false) String projectKey) {
        adminTokenValidator.require(adminToken);
        var keys = apiKeyRepository.listActive(blankToNull(projectKey)).stream()
                .map(McpKeySummary::from)
                .toList();
        return new McpKeyListResponse(keys, keys.size());
    }

    @PostMapping("/{id}/revoke")
    public McpKeyRevokeResponse revoke(@RequestHeader(ADMIN_TOKEN_HEADER) String adminToken,
            @PathVariable UUID id,
            @RequestBody(required = false) McpKeyRevokeRequest request) {
        adminTokenValidator.require(adminToken);
        Instant revokedAt = Instant.now();
        if (!apiKeyRepository.revoke(id, revokedAt)) {
            throw new IllegalArgumentException("Key not found or already revoked: " + id);
        }
        return new McpKeyRevokeResponse(id, true, revokedAt);
    }

    private static String normalizeScope(String scope) {
        return scope == null ? "" : scope.trim().toLowerCase(Locale.ROOT);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
