package com.mbworldwideapps.aiorchestration.modules.memoryai;

import java.util.List;
import java.util.Locale;

public final class MemoryScopeResolver {

    private MemoryScopeResolver() {
    }

    public static MemoryScopeResolution resolveExternal(String requestedScope, MemoryType type, String summary,
            String content, List<String> tags, String projectKey) {
        String normalizedScope = blankToNull(requestedScope);
        if (normalizedScope != null) {
            MemoryScope scope = MemoryScope.from(normalizedScope);
            if (scope != MemoryScope.GLOBAL && scope != MemoryScope.PROJECT) {
                throw new IllegalArgumentException("memory scope must be global or project");
            }
            if (type == MemoryType.DISCOVERY && scope != MemoryScope.PROJECT) {
                throw new IllegalArgumentException("discovery scope must be project");
            }
            return new MemoryScopeResolution(scope, "agent_selected", "scope parameter");
        }

        if (type == MemoryType.DISCOVERY) {
            return new MemoryScopeResolution(MemoryScope.PROJECT, "server_classified",
                    "discovery is project-scoped");
        }

        String haystack = normalize("%s %s %s".formatted(summary, content,
                tags == null ? "" : String.join(" ", tags)));
        String normalizedProjectKey = normalize(projectKey);
        if (!normalizedProjectKey.isBlank() && haystack.contains(normalizedProjectKey)) {
            return new MemoryScopeResolution(MemoryScope.PROJECT, "server_classified", "mentions project key");
        }
        if (containsAny(haystack, "bu proje", "bu repo", "this project", "this repo", "repository", "repo ",
                "src/", "pom.xml", "build.gradle", "application.yml", "controller endpoint", "mcp server")) {
            return new MemoryScopeResolution(MemoryScope.PROJECT, "server_classified", "repo-specific signal");
        }
        if (type == MemoryType.RULE || type == MemoryType.ANTI_PATTERN) {
            if (containsAny(haystack, "architecture", "architectural", "hexagonal", "ddd", "tdd", "clean code",
                    "clean-code", "extendable", "archunit", "archtest", "flyway", "controller service",
                    "domain", "application layer", "adapter", "ports", "mimari", "katman", "altyapi",
                    "altyapı", "temiz kod", "genisletilebilir", "genişletilebilir", "surdurulebilir",
                    "sürdürülebilir")) {
                return new MemoryScopeResolution(MemoryScope.GLOBAL, "server_classified",
                        "general engineering rule signal");
            }
        }
        return new MemoryScopeResolution(MemoryScope.PROJECT, "server_default", "no global signal");
    }

    private static boolean containsAny(String haystack, String... needles) {
        for (String needle : needles) {
            if (haystack.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
