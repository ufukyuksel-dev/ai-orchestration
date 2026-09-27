package com.mbworldwideapps.aiorchestration.modules.context;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Set;

import org.springframework.stereotype.Component;

/**
 * Deterministic, token-free routing. Change/debug work may consult learned
 * navigation without requiring a historic-decision keyword. Explicit bounded
 * resolves are keyword-independent; mechanical and self-contained requests
 * still abstain on the automatic injection path.
 */
@Component
class LearningContextRoutingPolicy {

    private static final Set<String> CODE_TERMS = Set.of(
            "code", "class", "method", "file", "symbol", "endpoint", "controller", "service", "repository",
            "config", "migration", "test", "flow", "caller", "callee", "dependency", "implementation",
            "kod", "sinif", "metot", "dosya", "sembol", "ayar", "testi", "akis", "nerede", "hangi");
    private static final Set<String> MEMORY_TERMS = Set.of(
            "why", "decision", "rule", "constraint", "incident", "lesson", "preference", "correction", "learned",
            "previous", "history", "rationale", "risk", "memory", "remember", "neden", "karar", "kural",
            "kisit", "olay", "ders", "tercih", "duzeltme", "ogrendik", "gecmis", "hafiza", "hatirla");
    private static final Set<String> NAVIGATION_TERMS = Set.of(
            "change", "modify", "update", "fix", "debug", "bug", "explain", "implement", "refactor",
            "degistir", "guncelle", "duzelt", "hata", "acikla", "uygula");

    LearningContextRoutingDecision decide(LearningContextRequest request, boolean memoryConfigured,
            boolean memoryInjectionEnabled) {
        return decide(request, memoryConfigured, memoryInjectionEnabled, false);
    }

    LearningContextRoutingDecision decide(LearningContextRequest request, boolean memoryConfigured,
            boolean memoryInjectionEnabled, boolean explicitResolve) {
        LearningContextMode requested = LearningContextMode.from(request.contextMode());
        String reason;
        if (requested != null) {
            reason = "explicit_request";
        } else if (explicitResolve) {
            requested = LearningContextMode.COMBINED;
            reason = "explicit_resolve";
        } else {
            boolean codeIntent = containsAny(request.query(), CODE_TERMS);
            boolean memoryIntent = containsAny(request.query(), MEMORY_TERMS);
            boolean navigationRole = isNavigationRole(request.role());
            boolean navigationIntent = containsAny(request.query(), NAVIGATION_TERMS) || navigationRole;
            if ((memoryIntent && codeIntent)
                    || (navigationIntent && navigationRole)) {
                requested = LearningContextMode.COMBINED;
                reason = memoryIntent ? "code_and_learned_decision_intent" : "learned_navigation_intent";
            } else if (memoryIntent) {
                requested = LearningContextMode.MEMORY_ONLY;
                reason = "learned_decision_intent";
            } else if (codeIntent || isCodeRole(request.role())) {
                requested = LearningContextMode.SCANNER_ONLY;
                reason = "current_code_intent";
            } else {
                requested = LearningContextMode.NONE;
                reason = "no_project_context_intent";
            }
        }

        if (Boolean.FALSE.equals(request.includeMemoryFallback()) && requested.includesMemory()) {
            LearningContextMode downgraded = requested.includesScanner()
                    ? LearningContextMode.SCANNER_ONLY
                    : LearningContextMode.NONE;
            return new LearningContextRoutingDecision(requested, downgraded, reason,
                    "memory_disabled_by_request");
        }
        if (requested.includesMemory() && !memoryConfigured) {
            LearningContextMode downgraded = requested.includesScanner()
                    ? LearningContextMode.SCANNER_ONLY
                    : LearningContextMode.NONE;
            return new LearningContextRoutingDecision(requested, downgraded, reason,
                    "memory_disabled_by_learning_context_config");
        }
        if (requested.includesMemory() && !memoryInjectionEnabled && !explicitResolve) {
            LearningContextMode downgraded = requested.includesScanner()
                    ? LearningContextMode.SCANNER_ONLY
                    : LearningContextMode.NONE;
            return new LearningContextRoutingDecision(requested, downgraded, reason,
                    "memory_injection_disabled");
        }
        return new LearningContextRoutingDecision(requested, requested, reason, "");
    }

    private static boolean isCodeRole(String role) {
        return role != null && ("analyst".equalsIgnoreCase(role.trim()) || "software".equalsIgnoreCase(role.trim()));
    }

    private static boolean isNavigationRole(String role) {
        if (role == null) return false;
        String normalized = role.trim().toLowerCase(Locale.ROOT);
        return Set.of("change", "debug", "explain").contains(normalized);
    }

    private static boolean containsAny(String query, Set<String> terms) {
        if (query == null || query.isBlank()) {
            return false;
        }
        String normalized = Normalizer.normalize(query, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT);
        for (String token : normalized.split("[^a-z0-9_]+")) {
            if (terms.contains(token)) {
                return true;
            }
        }
        return false;
    }
}
