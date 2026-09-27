package com.mbworldwideapps.aiorchestration.modules.mcp.server;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;

import com.mbworldwideapps.aiorchestration.config.LocalTrustProperties;
import com.mbworldwideapps.aiorchestration.core.security.LoopbackAddressMatcher;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@ConditionalOnProperty(prefix = "ai-orchestration.mcp", name = "enabled", havingValue = "true", matchIfMissing = true)
public class McpAuthenticationFilter extends OncePerRequestFilter {


    private final McpApiKeyRepository apiKeyRepository;
    private final McpAuditLogger auditLogger;
    private final LocalTrustProperties localTrustProperties;

    @Autowired
    public McpAuthenticationFilter(McpApiKeyRepository apiKeyRepository, McpAuditLogger auditLogger,
            LocalTrustProperties localTrustProperties) {
        this.apiKeyRepository = apiKeyRepository;
        this.auditLogger = auditLogger;
        this.localTrustProperties = localTrustProperties == null ? LocalTrustProperties.disabled() : localTrustProperties;
    }

    public McpAuthenticationFilter(McpApiKeyRepository apiKeyRepository, McpAuditLogger auditLogger) {
        this(apiKeyRepository, auditLogger, LocalTrustProperties.disabled());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (localTrustProperties.enabled()) {
            doLocalTrustFilter(request, response, filterChain);
            return;
        }

        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            denyAuth(request, response, "missing_bearer");
            return;
        }
        String token = authHeader.substring(7).trim();
        if (token.isBlank()) {
            denyAuth(request, response, "missing_bearer");
            return;
        }
        String hash = McpAuditLogger.sha256Hex(token);
        Optional<McpApiKey> match = apiKeyRepository.findActiveByHash(hash);
        if (match.isEmpty()) {
            denyAuth(request, response, "invalid_key");
            return;
        }
        McpApiKey apiKey = match.get();
        McpClientContextHolder.set(new McpClientContext(
                apiKey.projectKey(),
                apiKey.clientId(),
                apiKey.keyPrefix(),
                apiKey.scopes(),
                sessionScopeHash(request, apiKey.clientId())));
        try {
            try {
                apiKeyRepository.updateLastUsed(hash);
            } catch (RuntimeException ignored) {
                // Authentication must not fail because best-effort usage telemetry failed.
            }
            filterChain.doFilter(request, response);
        } finally {
            McpClientContextHolder.clear();
        }
    }

    private void doLocalTrustFilter(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String remoteAddress = request.getRemoteAddr();
        if (!LoopbackAddressMatcher.isLoopback(remoteAddress)) {
            denyLocalhost(request, response, remoteAddress);
            return;
        }
        McpClientContextHolder.set(new McpClientContext(
                localTrustProperties.defaultProjectKey(),
                localClientId(request),
                "local",
                localTrustProperties.effectiveLocalScopes(),
                sessionScopeHash(request, localClientId(request))));
        try {
            filterChain.doFilter(request, response);
        } finally {
            McpClientContextHolder.clear();
        }
    }

    private static String sessionScopeHash(HttpServletRequest request, String clientId) {
        String bridgeContext = request.getHeader("X-AI-Orch-Context-Key");
        String transportSession = request.getHeader("Mcp-Session-Id");
        String raw = bridgeContext != null && !bridgeContext.isBlank() ? bridgeContext
                : transportSession != null && !transportSession.isBlank() ? transportSession
                : null;
        if (raw == null) return McpClientContext.SESSION_SCOPE_UNAVAILABLE;
        return McpAuditLogger.sha256Hex((clientId == null ? "unknown" : clientId.trim()) + ":" + raw.trim());
    }

    private void denyAuth(HttpServletRequest request, HttpServletResponse response, String reason) throws IOException {
        auditLogger.log(null, "mcp.auth", null, 0, 0L, "denied_auth",
                Map.of("reason", reason, "path", request.getRequestURI()), null);
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"unauthorized\"}");
    }

    private void denyLocalhost(HttpServletRequest request, HttpServletResponse response, String remoteAddress)
            throws IOException {
        auditLogger.log(null, "mcp.auth", null, 0, 0L, "denied_localhost",
                Map.of("remoteAddress", remoteAddress == null ? "" : remoteAddress,
                        "path", request.getRequestURI()),
                null);
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"local_trust_requires_loopback\"}");
    }

    private String localClientId(HttpServletRequest request) {
        String raw = request.getHeader("X-AI-Orch-Client");
        if (raw == null || raw.isBlank()) {
            return localTrustProperties.defaultClientId();
        }
        String normalized = raw.trim()
                .replaceAll("[^A-Za-z0-9_.-]", "-");
        if (normalized.isBlank()) {
            return localTrustProperties.defaultClientId();
        }
        return normalized.length() > 64 ? normalized.substring(0, 64) : normalized;
    }
}
