package com.mbworldwideapps.aiorchestration.core.secrets;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ai-orchestration.secrets.vault")
public record VaultProperties(
        boolean enabled,
        String url,
        String token,
        String tokenRef,
        String namespace,
        long timeoutMs,
        boolean failFast) {

    public VaultProperties {
        if (url == null || url.isBlank()) {
            url = "http://localhost:8200";
        }
        url = url.replaceAll("/+$", "");
        if (token == null) {
            token = "";
        }
        if (tokenRef == null) {
            tokenRef = "";
        }
        if (namespace == null) {
            namespace = "";
        }
        if (timeoutMs <= 0) {
            timeoutMs = Duration.ofSeconds(3).toMillis();
        }
    }
}
