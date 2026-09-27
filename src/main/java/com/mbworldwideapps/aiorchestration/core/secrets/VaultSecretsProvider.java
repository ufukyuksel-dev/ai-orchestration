package com.mbworldwideapps.aiorchestration.core.secrets;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class VaultSecretsProvider implements SecretsProvider {

    private static final Logger log = LoggerFactory.getLogger(VaultSecretsProvider.class);
    private static final String VAULT_PREFIX = "vault:";

    private final VaultProperties properties;
    private final Environment environment;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    @Autowired
    public VaultSecretsProvider(VaultProperties properties, Environment environment, ObjectMapper objectMapper) {
        this(properties, environment, objectMapper, HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.timeoutMs()))
                .build());
    }

    VaultSecretsProvider(VaultProperties properties, Environment environment, ObjectMapper objectMapper,
            HttpClient httpClient) {
        this.properties = properties;
        this.environment = environment;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    @PostConstruct
    void preflight() {
        if (properties.enabled() && properties.failFast()) {
            healthCheck();
        }
    }

    @Override
    public Optional<String> getSecret(String path) {
        if (path == null || path.isBlank() || !path.startsWith(VAULT_PREFIX)) {
            return Optional.empty();
        }
        if (!properties.enabled()) {
            return Optional.empty();
        }
        VaultSecretReference reference = VaultSecretReference.parse(path);
        String token = resolveToken();
        if (token.isBlank()) {
            throw new IllegalStateException("Vault token is required when Vault secrets are enabled");
        }
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(properties.url() + "/v1/" + reference.path()))
                .timeout(Duration.ofMillis(properties.timeoutMs()))
                .GET()
                .header("X-Vault-Token", token);
        if (!properties.namespace().isBlank()) {
            requestBuilder.header("X-Vault-Namespace", properties.namespace());
        }
        try {
            HttpResponse<String> response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 404) {
                auditFetch(reference, "not-found");
                return Optional.empty();
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                auditFetch(reference, "failed-" + response.statusCode());
                throw new IllegalStateException("Vault secret fetch failed with status " + response.statusCode());
            }
            JsonNode root = objectMapper.readTree(response.body());
            Optional<String> value = extractValue(root, reference.key());
            auditFetch(reference, value.isPresent() ? "success" : "missing-key");
            return value;
        } catch (IOException e) {
            auditFetch(reference, "io-error");
            throw new IllegalStateException("Vault secret fetch failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            auditFetch(reference, "interrupted");
            throw new IllegalStateException("Vault secret fetch interrupted", e);
        }
    }

    private void healthCheck() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(properties.url() + "/v1/sys/health"))
                    .timeout(Duration.ofMillis(properties.timeoutMs()))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 500) {
                throw new IllegalStateException("Vault health check failed with status " + response.statusCode());
            }
        } catch (IOException e) {
            throw new IllegalStateException("Vault health check failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Vault health check interrupted", e);
        }
    }

    private String resolveToken() {
        if (!properties.token().isBlank()) {
            return properties.token();
        }
        if (!properties.tokenRef().isBlank() && properties.tokenRef().startsWith("env:")) {
            return environment.getProperty(properties.tokenRef().substring("env:".length()), "");
        }
        return "";
    }

    private Optional<String> extractValue(JsonNode root, String key) {
        JsonNode value = root.path("data").path("data").path(key);
        if (value.isMissingNode() || value.isNull()) {
            value = root.path("data").path(key);
        }
        if (value.isMissingNode() || value.isNull()) {
            return Optional.empty();
        }
        return Optional.of(value.asText());
    }

    private void auditFetch(VaultSecretReference reference, String decision) {
        log.info("vault_secret_fetch pathHash={} keyHash={} decision={}",
                Hashing.sha256Prefix(reference.path()), Hashing.sha256Prefix(reference.key()), decision);
    }

    private record VaultSecretReference(String path, String key) {

        static VaultSecretReference parse(String value) {
            String raw = value.substring(VAULT_PREFIX.length());
            int separator = raw.indexOf('#');
            if (separator < 0) {
                return new VaultSecretReference(stripSlash(raw), "value");
            }
            return new VaultSecretReference(stripSlash(raw.substring(0, separator)), raw.substring(separator + 1));
        }

        private static String stripSlash(String value) {
            return value.replaceAll("^/+", "");
        }
    }

    private static final class Hashing {
        private Hashing() {
        }

        static String sha256Prefix(String value) {
            try {
                java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
                byte[] hash = digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                StringBuilder builder = new StringBuilder();
                for (int i = 0; i < Math.min(8, hash.length); i++) {
                    builder.append(String.format("%02x", hash[i]));
                }
                return builder.toString();
            } catch (java.security.NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
