package com.mbworldwideapps.aiorchestration.core.judge;

import java.util.List;
import java.util.Locale;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ai-orchestration.judge")
public record JudgeProperties(
        String provider,
        boolean externalOptIn,
        List<String> externalProviders,
        int semanticPassThreshold,
        double minConceptOverlap) {

    public JudgeProperties {
        if (provider == null || provider.isBlank()) {
            provider = "claude-stub";
        }
        provider = provider.trim().toLowerCase(Locale.ROOT);
        if (externalProviders == null) {
            externalProviders = List.of();
        }
        externalProviders = externalProviders.stream()
                .filter(item -> item != null && !item.isBlank())
                .map(item -> item.trim().toLowerCase(Locale.ROOT))
                .toList();
        if (semanticPassThreshold <= 0) {
            semanticPassThreshold = 4;
        }
        if (minConceptOverlap <= 0.0 || minConceptOverlap > 1.0) {
            minConceptOverlap = 0.6;
        }
    }
}
