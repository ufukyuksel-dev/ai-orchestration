package com.mbworldwideapps.aiorchestration.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ai-orchestration.software.codegen.external-cli")
public record ExternalCliProviderProperties(
        Boolean enabled,
        String claudeCliPath,
        String claudeCliModel,
        long claudeCliTimeoutMs,
        List<String> claudeCliArgsExtra,
        String codexCliPath,
        String codexCliModel,
        long codexCliTimeoutMs,
        List<String> codexCliArgsExtra,
        Boolean fallbackToLocalQwen) {

    public ExternalCliProviderProperties {
        if (enabled == null) {
            enabled = true;
        }
        if (claudeCliPath == null || claudeCliPath.isBlank()) {
            claudeCliPath = "claude";
        } else {
            claudeCliPath = claudeCliPath.trim();
        }
        if (claudeCliModel != null) {
            claudeCliModel = claudeCliModel.trim();
        }
        if (claudeCliTimeoutMs <= 0) {
            claudeCliTimeoutMs = 60_000L;
        }
        claudeCliArgsExtra = claudeCliArgsExtra == null ? List.of()
                : claudeCliArgsExtra.stream()
                        .filter(value -> value != null && !value.isBlank())
                        .map(String::trim)
                        .toList();
        if (codexCliPath == null || codexCliPath.isBlank()) {
            codexCliPath = "codex";
        } else {
            codexCliPath = codexCliPath.trim();
        }
        if (codexCliModel != null) {
            codexCliModel = codexCliModel.trim();
        }
        if (codexCliTimeoutMs <= 0) {
            codexCliTimeoutMs = 60_000L;
        }
        codexCliArgsExtra = codexCliArgsExtra == null ? List.of()
                : codexCliArgsExtra.stream()
                        .filter(value -> value != null && !value.isBlank())
                        .map(String::trim)
                        .toList();
        if (fallbackToLocalQwen == null) {
            fallbackToLocalQwen = true;
        }
    }
}
