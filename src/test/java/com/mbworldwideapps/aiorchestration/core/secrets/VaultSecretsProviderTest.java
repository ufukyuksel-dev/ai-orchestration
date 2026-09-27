package com.mbworldwideapps.aiorchestration.core.secrets;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class VaultSecretsProviderTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void returnsEmptyWhenVaultDisabled() {
        VaultSecretsProvider provider = new VaultSecretsProvider(
                new VaultProperties(false, "http://localhost:8200", "root", "", "", 1000, false),
                new MockEnvironment(),
                new ObjectMapper());

        assertThat(provider.getSecret("vault:secret/data/app#password")).isEmpty();
    }

    @Test
    void resolvesKvV2Secret() throws Exception {
        server = startServer("""
                {"data":{"data":{"password":"vault-secret"}}}
                """);
        VaultSecretsProvider provider = new VaultSecretsProvider(
                new VaultProperties(true, baseUrl(), "root-token", "", "", 1000, false),
                new MockEnvironment(),
                new ObjectMapper());

        Optional<String> secret = provider.getSecret("vault:secret/data/ai-orchestration#password");

        assertThat(secret).contains("vault-secret");
    }

    @Test
    void compositeProviderRoutesEnvAndVaultReferences() throws Exception {
        server = startServer("""
                {"data":{"data":{"apiKey":"vault-api-key"}}}
                """);
        MockEnvironment environment = new MockEnvironment()
                .withProperty("AI_ORCH_TEST_SECRET", "env-secret");
        EnvironmentSecretsProvider envProvider = new EnvironmentSecretsProvider(environment);
        VaultSecretsProvider vaultProvider = new VaultSecretsProvider(
                new VaultProperties(true, baseUrl(), "root-token", "", "", 1000, false),
                environment,
                new ObjectMapper());
        DefaultSecretsProvider provider = new DefaultSecretsProvider(envProvider, vaultProvider);

        assertThat(provider.getSecret("env:AI_ORCH_TEST_SECRET")).contains("env-secret");
        assertThat(provider.getSecret("vault:secret/data/ai-orchestration#apiKey")).contains("vault-api-key");
    }

    @Test
    void versionedReadsObserveRotatedValuesWithoutCaching() throws Exception {
        server = startVersionedServer();
        VaultSecretsProvider provider = new VaultSecretsProvider(
                new VaultProperties(true, baseUrl(), "root-token", "", "", 1000, false),
                new MockEnvironment(),
                new ObjectMapper());

        assertThat(provider.getSecret("vault:secret/data/ai-orchestration?version=1#password"))
                .contains("vault-secret-v1");
        assertThat(provider.getSecret("vault:secret/data/ai-orchestration?version=2#password"))
                .contains("vault-secret-v2");
    }

    private HttpServer startServer(String body) throws IOException {
        HttpServer httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        httpServer.createContext("/v1/secret/data/ai-orchestration", exchange -> {
            assertThat(exchange.getRequestHeaders().getFirst("X-Vault-Token")).isEqualTo("root-token");
            byte[] response = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        httpServer.start();
        return httpServer;
    }

    private HttpServer startVersionedServer() throws IOException {
        HttpServer httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        httpServer.createContext("/v1/secret/data/ai-orchestration", exchange -> {
            assertThat(exchange.getRequestHeaders().getFirst("X-Vault-Token")).isEqualTo("root-token");
            String query = exchange.getRequestURI().getRawQuery();
            String secret = "version=1".equals(query) ? "vault-secret-v1" : "vault-secret-v2";
            byte[] response = """
                    {"data":{"data":{"password":"%s"}}}
                    """.formatted(secret).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        httpServer.start();
        return httpServer;
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }
}
