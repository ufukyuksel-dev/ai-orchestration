package com.mbworldwideapps.aiorchestration.core.judge;

import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

@Component
public class JudgeProviderPolicy {

    private static final Set<String> LOCAL_PROVIDERS = Set.of("claude-stub", "local-qwen", "literal", "semantic");

    private final JudgeProperties properties;

    public JudgeProviderPolicy(JudgeProperties properties) {
        this.properties = properties;
    }

    public JudgeProviderDecision select(String requestedProvider) {
        String provider = requestedProvider == null || requestedProvider.isBlank()
                ? properties.provider()
                : requestedProvider.trim().toLowerCase(java.util.Locale.ROOT);
        if (LOCAL_PROVIDERS.contains(provider)) {
            return new JudgeProviderDecision(provider, false);
        }
        Set<String> externalProviders = properties.externalProviders().stream().collect(Collectors.toUnmodifiableSet());
        if (properties.externalOptIn() && externalProviders.contains(provider)) {
            return new JudgeProviderDecision(provider, true);
        }
        throw new IllegalArgumentException("Judge provider is not allowed by policy: " + provider);
    }

    public JudgeProperties properties() {
        return properties;
    }
}
