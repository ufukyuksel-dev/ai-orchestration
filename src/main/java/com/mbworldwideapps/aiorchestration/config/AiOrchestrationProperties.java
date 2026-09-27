package com.mbworldwideapps.aiorchestration.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

@ConfigurationProperties(prefix = "ai-orchestration")
public record AiOrchestrationProperties(
        int chunkSizeCharacters,
        int chunkOverlapCharacters,
        int defaultTopK,
        String telemetryLogPath,
        int embeddingDimensions,
        String chatModel,
        Generation generation,
        Qdrant qdrant,
        Ollama ollama,
        Acl acl,
        Reranker reranker,
        Crawler crawler,
        Memory memory,
        Mcp mcp,
        int freshnessStaleAfterDays) {

    public AiOrchestrationProperties(int chunkSizeCharacters, int chunkOverlapCharacters, int defaultTopK,
            String telemetryLogPath, int embeddingDimensions, String chatModel, Generation generation, Qdrant qdrant,
            Ollama ollama, Acl acl, Reranker reranker, Crawler crawler, Memory memory,
            int freshnessStaleAfterDays) {
        this(chunkSizeCharacters, chunkOverlapCharacters, defaultTopK, telemetryLogPath, embeddingDimensions,
                chatModel, generation, qdrant, ollama, acl, reranker, crawler, memory, null,
                freshnessStaleAfterDays);
    }

    public AiOrchestrationProperties(int chunkSizeCharacters, int chunkOverlapCharacters, int defaultTopK,
            String telemetryLogPath, int embeddingDimensions, String chatModel, Generation generation, Qdrant qdrant,
            Ollama ollama, Acl acl, Reranker reranker, Crawler crawler, int freshnessStaleAfterDays) {
        this(chunkSizeCharacters, chunkOverlapCharacters, defaultTopK, telemetryLogPath, embeddingDimensions,
                chatModel, generation, qdrant, ollama, acl, reranker, crawler, null, null, freshnessStaleAfterDays);
    }

    @ConstructorBinding
    public AiOrchestrationProperties {
        if (freshnessStaleAfterDays <= 0) {
            freshnessStaleAfterDays = 365;
        }
        if (chatModel == null || chatModel.isBlank()) {
            chatModel = "qwen3:8b";
        }
        if (generation == null) {
            generation = new Generation(false, "local-qwen", true, 8000L);
        }
        if (qdrant == null) {
            qdrant = new Qdrant(false);
        }
        if (ollama == null) {
            ollama = new Ollama(false);
        }
        if (acl == null) {
            acl = new Acl("config/user-groups.yml", 300000L);
        }
        if (reranker == null) {
            reranker = new Reranker(true, "noop", 20, 2000L, 60, null, null);
        }
        if (crawler == null) {
            crawler = new Crawler(
                    java.util.List.of("accuweather.com", "developer.accuweather.com", "docs.spring.io",
                            "spring.io", "qdrant.tech", "qdrant.io"),
                    2,
                    25,
                    5_242_880L,
                    20_000L,
                    5,
                    java.util.List.of("text/html", "text/plain", "text/markdown", "application/xhtml+xml"),
                    1000L,
                    true);
        }
        if (memory == null) {
            memory = new Memory("memory_episodic_hash_384", 2000, 0.3,
                    "config/memory/global", ".ai_orch/memory", "AI_ORCHESTRATION", true,
                    false, 500, 700, 800, 5, 365);
        }
        if (mcp == null) {
            mcp = new Mcp(true, "sse", "/mcp", true, 5, 50, 10_000L);
        }
    }

    public record Qdrant(boolean checkCompatibilitySkipped) {
    }

    public record Ollama(boolean preflightEnabled) {
    }

    public record Mcp(
            boolean enabled,
            String transport,
            String basePath,
            boolean auditEnabled,
            int defaultTopK,
            int maxTopK,
            long requestTimeoutMs) {

        public Mcp {
            if (transport == null || transport.isBlank()) {
                transport = "sse";
            } else {
                transport = transport.trim().toLowerCase(java.util.Locale.ROOT);
            }
            if (!transport.equals("sse") && !transport.equals("http")) {
                transport = "sse";
            }
            if (basePath == null || basePath.isBlank()) {
                basePath = "/mcp";
            } else {
                basePath = basePath.trim();
                if (!basePath.startsWith("/")) {
                    basePath = "/" + basePath;
                }
            }
            if (basePath.endsWith("/") && basePath.length() > 1) {
                basePath = basePath.substring(0, basePath.length() - 1);
            }
            if (defaultTopK <= 0) {
                defaultTopK = 5;
            }
            if (maxTopK <= 0 || maxTopK < defaultTopK) {
                maxTopK = 50;
            }
            if (requestTimeoutMs <= 0) {
                requestTimeoutMs = 10_000L;
            }
        }
    }

    public record Generation(boolean enabled, String provider, boolean fallbackToExtractive, long timeoutMs,
            Canary canary) {

        public Generation(boolean enabled, String provider, boolean fallbackToExtractive, long timeoutMs) {
            this(enabled, provider, fallbackToExtractive, timeoutMs, null);
        }

        @ConstructorBinding
        public Generation {
            if (provider == null || provider.isBlank()) {
                provider = "local-qwen";
            }
            if (timeoutMs <= 0) {
                timeoutMs = 8000L;
            }
            if (canary == null) {
                canary = new Canary(false, java.util.List.of(), 0);
            }
        }
    }

    public record Canary(boolean enabled, java.util.List<String> allowedUserIds, int percentageRollout) {

        public Canary {
            if (allowedUserIds == null) {
                allowedUserIds = java.util.List.of();
            }
            allowedUserIds = allowedUserIds.stream()
                    .filter(value -> value != null && !value.isBlank())
                    .map(String::trim)
                    .toList();
            if (percentageRollout < 0) {
                percentageRollout = 0;
            }
            if (percentageRollout > 100) {
                percentageRollout = 100;
            }
        }
    }

    public record Acl(String userGroupsPath, long syncIntervalMs) {
    }

    public record Reranker(boolean enabled, String provider, int candidateLimit, long timeoutMs, int rrfK, Onnx onnx,
            java.util.Map<String, Double> sourceTypeBoost) {

        public Reranker(boolean enabled, String provider, int candidateLimit, long timeoutMs, int rrfK, Onnx onnx) {
            this(enabled, provider, candidateLimit, timeoutMs, rrfK, onnx, null);
        }

        public Reranker {
            if (provider == null || provider.isBlank()) {
                provider = "noop";
            }
            if (candidateLimit <= 0) {
                candidateLimit = 20;
            }
            if (timeoutMs <= 0) {
                timeoutMs = 2000L;
            }
            if (rrfK <= 0) {
                rrfK = 60;
            }
            if (onnx == null) {
                onnx = new Onnx(
                        "models/reranker/ms-marco-MiniLM-L6-v2/model_qint8_arm64.onnx",
                        "models/reranker/ms-marco-MiniLM-L6-v2/tokenizer.json",
                        "",
                        "",
                        512,
                        false);
            }
            if (sourceTypeBoost == null || sourceTypeBoost.isEmpty()) {
                sourceTypeBoost = defaultSourceTypeBoost();
            } else {
                sourceTypeBoost = sourceTypeBoost.entrySet().stream()
                        .filter(entry -> entry.getKey() != null && !entry.getKey().isBlank())
                        .filter(entry -> entry.getValue() != null && entry.getValue() > 0.0)
                        .collect(java.util.stream.Collectors.toUnmodifiableMap(
                                entry -> entry.getKey().trim(),
                                java.util.Map.Entry::getValue));
            }
        }
    }

    private static java.util.Map<String, Double> defaultSourceTypeBoost() {
        java.util.Map<String, Double> boosts = new java.util.LinkedHashMap<>();
        boosts.put("confluence-page", 1.5);
        boosts.put("github-source-file", 1.3);
        boosts.put("source-file", 1.3);
        boosts.put("jira-issue", 1.0);
        boosts.put("github-pr", 0.8);
        boosts.put("pr-comment", 0.8);
        boosts.put("git-commit", 0.5);
        return java.util.Collections.unmodifiableMap(boosts);
    }

    public record Onnx(
            String modelPath,
            String tokenizerPath,
            String modelSha256,
            String tokenizerSha256,
            int maxSequenceLength,
            boolean allowMissingModelFallback) {
    }

    public record Memory(
            String episodicCollectionName,
            int contextTokenCap,
            double defaultConfidence,
            String globalPath,
            String projectPath,
            String projectKey,
            boolean loadOnStartup,
            boolean injectionEnabled,
            int globalTokenCap,
            int projectTokenCap,
            int episodicTokenCap,
            int episodicTopK,
            int staleAfterDays) {

        public Memory(String episodicCollectionName, int contextTokenCap, double defaultConfidence) {
            this(episodicCollectionName, contextTokenCap, defaultConfidence,
                    "config/memory/global", ".ai_orch/memory", "AI_ORCHESTRATION", true,
                    false, 500, 700, 800, 5, 365);
        }

        public Memory(String episodicCollectionName, int contextTokenCap, double defaultConfidence,
                String globalPath, String projectPath, String projectKey, boolean loadOnStartup) {
            this(episodicCollectionName, contextTokenCap, defaultConfidence,
                    globalPath, projectPath, projectKey, loadOnStartup,
                    false, 500, 700, 800, 5, 365);
        }

        @ConstructorBinding
        public Memory {
            if (episodicCollectionName == null || episodicCollectionName.isBlank()) {
                episodicCollectionName = "memory_episodic_hash_384";
            }
            if (contextTokenCap <= 0) {
                contextTokenCap = 2000;
            }
            if (defaultConfidence < 0.0 || defaultConfidence > 1.0) {
                defaultConfidence = 0.3;
            }
            if (globalPath == null || globalPath.isBlank()) {
                globalPath = "config/memory/global";
            }
            if (projectPath == null || projectPath.isBlank()) {
                projectPath = ".ai_orch/memory";
            }
            if (projectKey == null || projectKey.isBlank()) {
                projectKey = "AI_ORCHESTRATION";
            }
            if (globalTokenCap <= 0) {
                globalTokenCap = 500;
            }
            if (projectTokenCap <= 0) {
                projectTokenCap = 700;
            }
            if (episodicTokenCap <= 0) {
                episodicTokenCap = 800;
            }
            if (episodicTopK <= 0) {
                episodicTopK = 5;
            }
            if (staleAfterDays <= 0) {
                staleAfterDays = 365;
            }
        }
    }

    public record Crawler(
            java.util.List<String> allowedDomains,
            int maxDepth,
            int maxPages,
            long maxBytes,
            long requestTimeoutMs,
            int maxRedirects,
            java.util.List<String> allowedContentTypes,
            long crawlDelayMs,
            boolean robotsEnabled) {

        public Crawler {
            if (allowedDomains == null) {
                allowedDomains = java.util.List.of();
            }
            allowedDomains = allowedDomains.stream()
                    .filter(domain -> domain != null && !domain.isBlank())
                    .map(domain -> domain.trim().toLowerCase(java.util.Locale.ROOT))
                    .toList();
            if (maxDepth < 0) {
                maxDepth = 0;
            }
            if (maxPages <= 0) {
                maxPages = 25;
            }
            if (maxBytes <= 0) {
                maxBytes = 5_242_880L;
            }
            if (requestTimeoutMs <= 0) {
                requestTimeoutMs = 20_000L;
            }
            if (maxRedirects < 0) {
                maxRedirects = 0;
            }
            if (allowedContentTypes == null || allowedContentTypes.isEmpty()) {
                allowedContentTypes = java.util.List.of("text/html", "text/plain", "text/markdown",
                        "application/xhtml+xml");
            }
            allowedContentTypes = allowedContentTypes.stream()
                    .filter(contentType -> contentType != null && !contentType.isBlank())
                    .map(contentType -> contentType.trim().toLowerCase(java.util.Locale.ROOT))
                    .toList();
            if (crawlDelayMs < 0) {
                crawlDelayMs = 0L;
            }
        }
    }
}
