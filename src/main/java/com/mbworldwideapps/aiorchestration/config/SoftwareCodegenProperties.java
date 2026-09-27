package com.mbworldwideapps.aiorchestration.config;

import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ai-orchestration.software.codegen")
public record SoftwareCodegenProperties(
        Boolean enabled,
        int maxRetryAttempts,
        int repoContextTokenCap,
        int testFeedbackMaxLines,
        Map<String, String> languageProviderMap,
        int smallEditLineThreshold,
        long codegenTimeoutMs,
        int codegenMaxTokens) {

    public SoftwareCodegenProperties {
        if (enabled == null) {
            enabled = true;
        }
        if (maxRetryAttempts <= 0 || maxRetryAttempts > 5) {
            maxRetryAttempts = 3;
        }
        if (repoContextTokenCap <= 0) {
            repoContextTokenCap = 20_000;
        }
        if (testFeedbackMaxLines <= 0) {
            testFeedbackMaxLines = 200;
        }
        if (languageProviderMap == null || languageProviderMap.isEmpty()) {
            languageProviderMap = Map.of(
                    "java", "claude",
                    "kt", "claude",
                    "kts", "claude",
                    "kotlin", "claude",
                    "js", "codex",
                    "jsx", "codex",
                    "ts", "codex",
                    "tsx", "codex",
                    "py", "claude",
                    "default", "local-qwen");
        } else {
            languageProviderMap = Map.copyOf(languageProviderMap);
        }
        if (smallEditLineThreshold <= 0) {
            smallEditLineThreshold = 20;
        }
        if (codegenTimeoutMs <= 0) {
            codegenTimeoutMs = 60_000L;
        }
        if (codegenMaxTokens <= 0) {
            codegenMaxTokens = 4_096;
        }
    }

    public static SoftwareCodegenProperties disabledDefaults() {
        return new SoftwareCodegenProperties(false, 3, 20_000, 200, null, 20, 60_000L, 4_096);
    }
}
