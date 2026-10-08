package com.mbworldwideapps.aiorchestration.modules.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mbworldwideapps.aiorchestration.config.AiOrchestrationProperties;
import com.mbworldwideapps.aiorchestration.config.LocalTrustProperties;
import org.junit.jupiter.api.Test;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.stereotype.Component;

/**
 * Claude Code, Codex and Copilot connect with different client headers (Claude Code also used to send
 * X-AI-Orch-Tools: minimal); all of them must see the same tool list and the same local-trust scopes.
 */
class McpClientParityTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void everyAgentClientSeesTheSameToolsAndScopes() throws Exception {
        McpAuditLogger audit = mock(McpAuditLogger.class);
        McpAuthenticationFilter auth = new McpAuthenticationFilter(mock(McpApiKeyRepository.class), audit,
                new LocalTrustProperties(true, "PROJECT_A", "local-client", null));
        AiOrchestrationProperties properties = mock(AiOrchestrationProperties.class, RETURNS_DEEP_STUBS);
        when(properties.mcp().basePath()).thenReturn("/mcp");
        ScopeProbe probe = new ScopeProbe();
        try (var context = new SpringApplicationBuilder(InstructionHttpFixture.class)
                .properties("spring.config.name=client-parity-test", "server.address=127.0.0.1", "server.port=0",
                        "spring.ai.mcp.server.protocol=STREAMABLE", "spring.ai.mcp.server.type=SYNC")
                .initializers(c -> {
                    ((BeanDefinitionRegistry) c.getBeanFactory()).registerBeanDefinition("scopeProbe",
                            new RootBeanDefinition(ScopeProbe.class, () -> probe));
                    c.getBeanFactory().registerSingleton("authFilter",
                            new McpServerConfiguration().mcpAuthFilterRegistration(auth, properties));
                }).run()) {
            int port = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
            URI endpoint = URI.create("http://127.0.0.1:" + port + "/mcp");
            HttpClient client = HttpClient.newHttpClient();

            List<Map<String, String>> clients = List.of(
                    Map.of("X-AI-Orch-Client", "claude-code", "X-AI-Orch-Tools", "minimal"),
                    Map.of("X-AI-Orch-Client", "codex"),
                    Map.of("X-AI-Orch-Client", "copilot-cli-memory-skill"));
            List<JsonNode> tools = new ArrayList<>();
            List<String> scopes = new ArrayList<>();
            for (Map<String, String> headers : clients) {
                var init = post(client, endpoint, headers, null, """
                        {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26",
                         "capabilities":{},"clientInfo":{"name":"parity-test","version":"1"}}}
                        """);
                assertThat(init.statusCode()).isEqualTo(200);
                String session = init.headers().firstValue("mcp-session-id").orElseThrow();
                post(client, endpoint, headers, session, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
                tools.add(result(post(client, endpoint, headers, session,
                        "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}").body()).path("tools"));
                String call = post(client, endpoint, headers, session, """
                        {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"parity.scopes","arguments":{}}}
                        """).body();
                scopes.add(result(call).path("content").path(0).path("text").asText());
            }

            assertThat(tools.get(0).size()).isGreaterThanOrEqualTo(2);
            assertThat(tools.get(0).toString()).contains("parity.scopes", "parity.other");
            assertThat(tools).allSatisfy(list -> assertThat(list).isEqualTo(tools.get(0)));
            assertThat(scopes.get(0)).contains("memory.approve", "memory.admin", "memory.auto_active_write",
                    "provider.*");
            assertThat(scopes).allSatisfy(s -> assertThat(s).isEqualTo(scopes.get(0)));
        }
    }

    private JsonNode result(String body) throws Exception {
        for (String line : body.split("\n")) {
            String payload = line.startsWith("data:") ? line.substring(5).trim() : line.trim();
            if (payload.startsWith("{")) {
                JsonNode node = json.readTree(payload);
                if (node.has("result")) return node.path("result");
            }
        }
        throw new AssertionError("no JSON-RPC result in " + body);
    }

    private static HttpResponse<String> post(HttpClient client, URI endpoint, Map<String, String> headers,
            String session, String payload) throws Exception {
        var request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream");
        headers.forEach(request::header);
        if (session != null) request.header("Mcp-Session-Id", session);
        return client.send(request.POST(HttpRequest.BodyPublishers.ofString(payload)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Component
    public static class ScopeProbe {
        @McpTool(name = "parity.scopes", description = "Scopes of the calling client.")
        public String scopes() {
            return String.join(",", McpClientContextHolder.require().scopes());
        }

        @McpTool(name = "parity.other", description = "A second tool.")
        public String other() {
            return "ok";
        }
    }

    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @org.springframework.boot.autoconfigure.ImportAutoConfiguration({
        org.springframework.boot.autoconfigure.web.servlet.ServletWebServerFactoryAutoConfiguration.class,
        org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration.class,
        org.springframework.boot.autoconfigure.web.servlet.DispatcherServletAutoConfiguration.class,
        org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration.class,
        org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration.class,
        org.springframework.ai.mcp.server.common.autoconfigure.McpServerAutoConfiguration.class,
        org.springframework.ai.mcp.server.common.autoconfigure.McpServerObjectMapperAutoConfiguration.class,
        org.springframework.ai.mcp.server.common.autoconfigure.ToolCallbackConverterAutoConfiguration.class,
        org.springframework.ai.mcp.server.common.autoconfigure.annotations.McpServerSpecificationFactoryAutoConfiguration.class,
        org.springframework.ai.mcp.server.common.autoconfigure.annotations.McpServerAnnotationScannerAutoConfiguration.class,
        org.springframework.ai.mcp.server.autoconfigure.McpServerStreamableHttpWebMvcAutoConfiguration.class
    })
    static class InstructionHttpFixture { }
}
