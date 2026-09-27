package com.mbworldwideapps.aiorchestration.modules.mcp.server;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

import com.mbworldwideapps.aiorchestration.config.AiOrchestrationProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class McpAuditLogger {

    private static final Logger log = LoggerFactory.getLogger(McpAuditLogger.class);

    private final McpAccessLogRepository repository;
    private final AiOrchestrationProperties properties;

    public McpAuditLogger(McpAccessLogRepository repository, AiOrchestrationProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    public void log(McpClientContext context, String toolName, String query, int resultCount, Instant started,
            String decision) {
        log(context, toolName, hashQuery(query), resultCount, latencyMs(started), decision, Map.of(), null);
    }

    public void log(McpClientContext context, String toolName, String queryHash, int resultCount, long latencyMs,
            String decision, Map<String, Object> metadata, String errorClass) {
        if (!properties.mcp().auditEnabled()) {
            return;
        }
        try {
            repository.insert(new McpAccessLogEntry(
                    UUID.randomUUID(),
                    Instant.now(),
                    context == null ? null : context.projectKey(),
                    context == null ? null : context.clientId(),
                    context == null ? null : context.keyPrefix(),
                    toolName,
                    queryHash,
                    resultCount,
                    Math.toIntExact(Math.min(Integer.MAX_VALUE, Math.max(0, latencyMs))),
                    decision,
                    errorClass,
                    metadata == null ? Map.of() : metadata));
        } catch (RuntimeException e) {
            log.warn("Failed to write MCP audit log entry for tool={} decision={}", toolName, decision, e);
        }
    }

    public static String hashQuery(String query) {
        if (query == null) {
            return null;
        }
        return sha256Hex(query);
    }

    public static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static long latencyMs(Instant started) {
        return started == null ? 0L : java.time.Duration.between(started, Instant.now()).toMillis();
    }
}
