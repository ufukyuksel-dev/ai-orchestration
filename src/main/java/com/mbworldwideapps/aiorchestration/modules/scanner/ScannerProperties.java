package com.mbworldwideapps.aiorchestration.modules.scanner;

import java.util.List;
import java.util.Locale;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

@ConfigurationProperties(prefix = "ai-orchestration.scanner")
public record ScannerProperties(
        String projectKey,
        List<String> supportedExtensions,
        List<String> excludedDirectories,
        long maxFileBytes,
        double defaultConfidence,
        String baselineCollectionName,
        List<String> allowedRoots,
        Semantic semantic,
        ResolvedEdges resolvedEdges) {

    public ScannerProperties(String projectKey, List<String> supportedExtensions, List<String> excludedDirectories,
            long maxFileBytes, double defaultConfidence) {
        this(projectKey, supportedExtensions, excludedDirectories, maxFileBytes, defaultConfidence, null, null, null,
                null);
    }

    public ScannerProperties(String projectKey, List<String> supportedExtensions, List<String> excludedDirectories,
            long maxFileBytes, double defaultConfidence, Semantic semantic) {
        this(projectKey, supportedExtensions, excludedDirectories, maxFileBytes, defaultConfidence, null, null,
                semantic, null);
    }

    public ScannerProperties(String projectKey, List<String> supportedExtensions, List<String> excludedDirectories,
            long maxFileBytes, double defaultConfidence, List<String> allowedRoots, Semantic semantic) {
        this(projectKey, supportedExtensions, excludedDirectories, maxFileBytes, defaultConfidence, null,
                allowedRoots, semantic, null);
    }

    public ScannerProperties(String projectKey, List<String> supportedExtensions, List<String> excludedDirectories,
            long maxFileBytes, double defaultConfidence, List<String> allowedRoots, Semantic semantic,
            ResolvedEdges resolvedEdges) {
        this(projectKey, supportedExtensions, excludedDirectories, maxFileBytes, defaultConfidence, null,
                allowedRoots, semantic, resolvedEdges);
    }

    @ConstructorBinding
    public ScannerProperties {
        if (projectKey == null || projectKey.isBlank()) {
            projectKey = "AI_ORCHESTRATION";
        }
        if (supportedExtensions == null || supportedExtensions.isEmpty()) {
            supportedExtensions = List.of(".java", ".py", ".kt", ".swift", "pom.xml", "package.json");
        }
        supportedExtensions = supportedExtensions.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .toList();
        if (excludedDirectories == null || excludedDirectories.isEmpty()) {
            excludedDirectories = List.of(".git", "target", "node_modules", "output", "logs", "models", "artifacts",
                    "build", ".gradle", "Pods", "DerivedData", ".build", "Carthage");
        }
        excludedDirectories = excludedDirectories.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .toList();
        if (maxFileBytes <= 0) {
            maxFileBytes = 512_000L;
        }
        if (defaultConfidence <= 0.0 || defaultConfidence > 1.0) {
            defaultConfidence = 0.6;
        }
        if (baselineCollectionName == null || baselineCollectionName.isBlank()) {
            baselineCollectionName = "code_baseline_bge_m3_1024";
        } else {
            baselineCollectionName = baselineCollectionName.trim();
        }
        if (allowedRoots == null || allowedRoots.isEmpty()) {
            allowedRoots = List.of(".");
        }
        allowedRoots = allowedRoots.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .toList();
        if (allowedRoots.isEmpty()) {
            allowedRoots = List.of(".");
        }
        if (semantic == null) {
            semantic = Semantic.defaults();
        }
        if (resolvedEdges == null) {
            resolvedEdges = ResolvedEdges.defaults();
        }
    }

    public record Semantic(
            boolean enabled,
            String provider,
            String model,
            boolean allowExternal,
            List<String> allowedProviders,
            long timeoutMs,
            int maxFilesPerRun,
            int maxMethodsPerFile,
            int maxPromptTokens,
            int maxOutputTokens,
            String promptVersion,
            int maxFlowCapsulesPerRun,
            SemanticSelectionMode selectionMode) {

        public static Semantic defaults() {
            return new Semantic(false, "local-qwen", "qwen3:8b", false, List.of("local-qwen"),
                    8000L, 200, 12, 6000, 512, "scanner-semantic-v1", 24, SemanticSelectionMode.ALL);
        }

        public Semantic {
            if (provider == null || provider.isBlank()) {
                provider = "local-qwen";
            } else {
                provider = provider.trim().toLowerCase(Locale.ROOT);
                if ("qwen".equals(provider)) {
                    provider = "local-qwen";
                }
            }
            if (model == null || model.isBlank()) {
                model = "qwen3:8b";
            } else {
                model = model.trim();
            }
            if (allowedProviders == null || allowedProviders.isEmpty()) {
                allowedProviders = List.of("local-qwen");
            }
            allowedProviders = allowedProviders.stream()
                    .filter(value -> value != null && !value.isBlank())
                    .map(value -> value.trim().toLowerCase(Locale.ROOT))
                    .map(value -> "qwen".equals(value) ? "local-qwen" : value)
                    .distinct()
                    .toList();
            if (timeoutMs <= 0) {
                timeoutMs = 8000L;
            }
            if (maxFilesPerRun <= 0) {
                maxFilesPerRun = 200;
            }
            if (maxMethodsPerFile <= 0) {
                maxMethodsPerFile = 12;
            }
            if (maxPromptTokens <= 0) {
                maxPromptTokens = 6000;
            }
            if (maxOutputTokens <= 0) {
                maxOutputTokens = 512;
            }
            if (promptVersion == null || promptVersion.isBlank()) {
                promptVersion = "scanner-semantic-v1";
            } else {
                promptVersion = promptVersion.trim();
            }
            if (maxFlowCapsulesPerRun <= 0) {
                maxFlowCapsulesPerRun = 24;
            }
            if (selectionMode == null) {
                selectionMode = SemanticSelectionMode.ALL;
            }
        }
    }

    public record ResolvedEdges(
            boolean enabled,
            int maxFilesPerRun,
            int maxClasspathJars) {

        public static ResolvedEdges defaults() {
            return new ResolvedEdges(false, 1000, 200);
        }

        public ResolvedEdges {
            if (maxFilesPerRun <= 0) {
                maxFilesPerRun = 1000;
            }
            if (maxClasspathJars <= 0) {
                maxClasspathJars = 200;
            }
        }
    }
}
