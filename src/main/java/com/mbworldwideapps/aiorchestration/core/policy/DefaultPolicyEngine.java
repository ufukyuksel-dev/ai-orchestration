package com.mbworldwideapps.aiorchestration.core.policy;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryScope;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemorySourceType;
import org.springframework.stereotype.Component;

@Component
public class DefaultPolicyEngine implements PolicyEngine {

    public static final String REASON_WRITE_SOURCE_SCOPE_DENIED = "write-source-scope-denied";
    public static final String REASON_SENSITIVE_TERM = "sensitive-term";

    private final PolicyProperties properties;
    private final Map<MemorySourceType, Set<MemoryScope>> allowedWrites;
    private final Set<String> normalizedSensitiveTerms;

    public DefaultPolicyEngine(PolicyProperties properties) {
        this.properties = properties;
        this.allowedWrites = allowedWrites();
        this.normalizedSensitiveTerms = properties.sensitiveTerms().stream()
                .map(DefaultPolicyEngine::normalize)
                .filter(term -> !term.isBlank())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    @Override
    public PolicyDecision evaluateMemoryWrite(MemorySourceType sourceType, MemoryScope scope) {
        if (!properties.enabled()) {
            return PolicyDecision.allow();
        }
        if (sourceType == null || scope == null) {
            return PolicyDecision.deny(REASON_WRITE_SOURCE_SCOPE_DENIED);
        }
        Set<MemoryScope> allowedScopes = allowedWrites.getOrDefault(sourceType, Set.of());
        return allowedScopes.contains(scope)
                ? PolicyDecision.allow()
                : PolicyDecision.deny(REASON_WRITE_SOURCE_SCOPE_DENIED);
    }

    @Override
    public PolicyDecision evaluateMemoryContent(String text) {
        if (!properties.enabled() || text == null || text.isBlank()) {
            return PolicyDecision.allow();
        }
        String normalizedText = normalize(text);
        boolean containsSensitiveTerm = normalizedSensitiveTerms.stream().anyMatch(normalizedText::contains);
        return containsSensitiveTerm ? PolicyDecision.deny(REASON_SENSITIVE_TERM) : PolicyDecision.allow();
    }

    private static Map<MemorySourceType, Set<MemoryScope>> allowedWrites() {
        Map<MemorySourceType, Set<MemoryScope>> rules = new EnumMap<>(MemorySourceType.class);
        rules.put(MemorySourceType.MANUAL, EnumSet.of(
                MemoryScope.GLOBAL, MemoryScope.PROJECT, MemoryScope.USER, MemoryScope.EPISODIC));
        rules.put(MemorySourceType.CORRECTION_SIGNAL, EnumSet.of(MemoryScope.PROJECT, MemoryScope.EPISODIC));
        rules.put(MemorySourceType.APPROVAL_SIGNAL, EnumSet.of(MemoryScope.PROJECT, MemoryScope.EPISODIC));
        rules.put(MemorySourceType.SESSION_SUMMARY, EnumSet.of(MemoryScope.PROJECT, MemoryScope.EPISODIC));
        rules.put(MemorySourceType.SCANNED, EnumSet.of(MemoryScope.PROJECT, MemoryScope.EPISODIC));
        rules.put(MemorySourceType.MCP_EXTERNAL, EnumSet.of(MemoryScope.GLOBAL, MemoryScope.PROJECT));
        rules.put(MemorySourceType.AUTO_CURATED, EnumSet.of(MemoryScope.GLOBAL, MemoryScope.PROJECT));
        return Map.copyOf(rules);
    }

    private static String normalize(String value) {
        return value.trim().toLowerCase(Locale.forLanguageTag("tr-TR"));
    }
}
