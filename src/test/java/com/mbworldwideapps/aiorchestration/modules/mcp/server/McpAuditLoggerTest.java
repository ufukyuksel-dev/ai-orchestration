package com.mbworldwideapps.aiorchestration.modules.mcp.server;

import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.List;

import com.mbworldwideapps.aiorchestration.config.AiOrchestrationProperties;
import org.junit.jupiter.api.Test;

class McpAuditLoggerTest {

    @Test
    void auditRepositoryFailureDoesNotFailCaller() {
        McpAccessLogRepository failingRepository = entry -> {
            throw new IllegalStateException("audit db unavailable");
        };
        McpAuditLogger logger = new McpAuditLogger(failingRepository, properties());

        assertThatCode(() -> logger.log(
                new McpClientContext("PROJECT_A", "self-pipeline", "mcp_abcd", List.of("memory.read")),
                "memory.search",
                "query",
                1,
                java.time.Instant.now(),
                "success"))
                .doesNotThrowAnyException();
    }

    private static AiOrchestrationProperties properties() {
        return new AiOrchestrationProperties(
                1200,
                160,
                5,
                "logs/test-mcp-audit.jsonl",
                384,
                "qwen3:8b",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                new AiOrchestrationProperties.Mcp(true, "sse", "/mcp", true, 5, 50, 10_000L),
                365);
    }
}
