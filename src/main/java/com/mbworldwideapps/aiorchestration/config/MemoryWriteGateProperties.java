package com.mbworldwideapps.aiorchestration.config;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ai-orchestration.memory.write-gate")
public record MemoryWriteGateProperties(
        String provider,
        String sessionFallbackProvider,
        Double autoActiveConfidence,
        Duration gateTimeout,
        Set<String> trustedClientIds) {

    public static final String PROVIDER_SESSION = "sessionProvider";
    public static final String PROVIDER_QWEN = "local-qwen";
    public static final String PROVIDER_CLAUDE = "claude";
    public static final String PROVIDER_CODEX = "codex";

    public MemoryWriteGateProperties {
        provider = canonicalProvider(provider, true, "provider");
        if (PROVIDER_SESSION.equals(provider)) {
            sessionFallbackProvider = canonicalProvider(sessionFallbackProvider, false, "sessionFallbackProvider");
        } else {
            sessionFallbackProvider = blankToNull(sessionFallbackProvider) == null
                    ? null
                    : canonicalProvider(sessionFallbackProvider, false, "sessionFallbackProvider");
        }
        if (autoActiveConfidence == null) {
            autoActiveConfidence = 0.85;
        }
        if (autoActiveConfidence <= 0.0 || autoActiveConfidence > 1.0) {
            throw new IllegalArgumentException("memory.write-gate.auto-active-confidence must be > 0 and <= 1");
        }
        if (gateTimeout == null) {
            gateTimeout = Duration.ofSeconds(3);
        }
        if (gateTimeout.isZero() || gateTimeout.isNegative()) {
            throw new IllegalArgumentException("memory.write-gate.gate-timeout must be positive");
        }
        trustedClientIds = normalizeTrustedClients(trustedClientIds);
    }

    public boolean sessionProviderMode() {
        return PROVIDER_SESSION.equals(provider);
    }

    public static String canonicalProvider(String value, boolean allowSessionProvider, String fieldName) {
        String normalized = blankToNull(value);
        if (normalized == null) {
            throw new IllegalArgumentException("memory.write-gate." + fieldName + " is required");
        }
        String lower = normalized.toLowerCase(Locale.ROOT);
        if ("heuristic".equals(lower) || "none".equals(lower)) {
            return "heuristic";  // no LLM call: duplicate check by embedding similarity only
        }
        if ("qwen".equals(lower) || "local-qwen".equals(lower)) {
            return PROVIDER_QWEN;
        }
        if ("claude".equals(lower)) {
            return PROVIDER_CLAUDE;
        }
        if ("codex".equals(lower)) {
            return PROVIDER_CODEX;
        }
        if (allowSessionProvider && "sessionprovider".equals(lower)) {
            return PROVIDER_SESSION;
        }
        throw new IllegalArgumentException("Unsupported memory.write-gate." + fieldName + ": " + value);
    }

    private static Set<String> normalizeTrustedClients(Set<String> trustedClientIds) {
        Set<String> source = trustedClientIds == null
                ? Set.of("self-pipeline", "claude-code", "codex")
                : trustedClientIds;
        return source.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .collect(Collectors.collectingAndThen(Collectors.toCollection(LinkedHashSet::new), Set::copyOf));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
