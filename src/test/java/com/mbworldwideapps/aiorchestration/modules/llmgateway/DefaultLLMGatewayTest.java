package com.mbworldwideapps.aiorchestration.modules.llmgateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.mbworldwideapps.aiorchestration.config.AiOrchestrationProperties;
import com.mbworldwideapps.aiorchestration.modules.scanner.ScannerProperties;
import org.junit.jupiter.api.Test;

class DefaultLLMGatewayTest {

    @Test
    void dispatchesToSelectedProvider() {
        LLMProvider first = provider("local-qwen");
        LLMProvider second = provider("claude-stub");
        ExecutorService executorService = Executors.newSingleThreadExecutor();
        try {
            DefaultProviderRegistry registry = new DefaultProviderRegistry(List.of(first, second), properties(),
                    executorService);
            DefaultLLMGateway gateway = new DefaultLLMGateway(
                    request -> request.requestedProvider(),
                    registry);

            GenerationResponse response = gateway.generate(new GenerationRequest(
                    "q",
                    "u",
                    List.of(),
                    "fresh",
                    "claude-stub",
                    null,
                    0,
                    "test"));

            assertThat(response.provider()).isEqualTo("claude-stub");
            assertThat(registry.providerIds()).containsExactlyInAnyOrder("local-qwen", "claude-stub");
        } finally {
            executorService.shutdownNow();
        }
    }

    @Test
    void scannerRolesUseScannerSemanticTimeout() {
        LLMProvider slow = slowProvider("codex", 80L);
        ExecutorService executorService = Executors.newSingleThreadExecutor();
        try {
            DefaultProviderRegistry registry = new DefaultProviderRegistry(List.of(slow), properties(),
                    scannerProperties(10L), executorService, executorService);
            DefaultLLMGateway gateway = new DefaultLLMGateway(
                    request -> request.requestedProvider(),
                    registry);

            for (String role : List.of("scanner", "scanner-flow")) {
                assertThatThrownBy(() -> gateway.generate(new GenerationRequest(
                        "q",
                        "u",
                        List.of(),
                        "fresh",
                        "codex",
                        null,
                        0,
                        role)))
                        .isInstanceOf(LLMProviderTimeoutException.class)
                        .hasMessageContaining("codex after 10 ms");
            }
        } finally {
            executorService.shutdownNow();
        }
    }

    @Test
    void scannerRoleRunsOnDedicatedScannerExecutor() {
        java.util.List<String> threads = new java.util.concurrent.CopyOnWriteArrayList<>();
        LLMProvider probe = new LLMProvider() {
            @Override
            public String id() {
                return "codex";
            }

            @Override
            public GenerationResponse generate(GenerationRequest request) {
                threads.add(Thread.currentThread().getName());
                return new GenerationResponse("ok", "codex", 1L, false, DegradedReason.NONE, 1, 1, 0, 0, 0L);
            }
        };
        ExecutorService sharedExecutor = Executors.newSingleThreadExecutor(
                runnable -> new Thread(runnable, "shared-gateway-pool"));
        ExecutorService scannerExecutor = Executors.newSingleThreadExecutor(
                runnable -> new Thread(runnable, "scanner-pool"));
        try {
            DefaultProviderRegistry registry = new DefaultProviderRegistry(List.of(probe), properties(),
                    scannerProperties(1000L), sharedExecutor, scannerExecutor);
            LLMProvider timeBoxed = registry.get("codex");

            timeBoxed.generate(new GenerationRequest("q", "u", List.of(), "fresh", "codex", null, 0, "scanner-flow"));
            timeBoxed.generate(new GenerationRequest("q", "u", List.of(), "fresh", "codex", null, 0, "orchestrator"));

            assertThat(threads).containsExactly("scanner-pool", "shared-gateway-pool");
        } finally {
            sharedExecutor.shutdownNow();
            scannerExecutor.shutdownNow();
        }
    }

    private static LLMProvider provider(String id) {
        return new LLMProvider() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public GenerationResponse generate(GenerationRequest request) {
                return new GenerationResponse("answer", id, 1L, false, DegradedReason.NONE, 1, 1, 0, 0, 0L);
            }
        };
    }

    private static LLMProvider slowProvider(String id, long sleepMs) {
        return new LLMProvider() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public GenerationResponse generate(GenerationRequest request) {
                try {
                    Thread.sleep(sleepMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return new GenerationResponse("late", id, sleepMs, false, DegradedReason.NONE, 1, 1, 0, 0, 0L);
            }
        };
    }

    private static AiOrchestrationProperties properties() {
        return new AiOrchestrationProperties(
                1200,
                160,
                5,
                "logs/test.jsonl",
                384,
                "qwen3:8b",
                new AiOrchestrationProperties.Generation(true, "local-qwen", true, 8000L),
                new AiOrchestrationProperties.Qdrant(true),
                new AiOrchestrationProperties.Ollama(false),
                new AiOrchestrationProperties.Acl("config/user-groups.yml", 300000L),
                new AiOrchestrationProperties.Reranker(true, "noop", 20, 2000L, 60, null),
                null,
                365);
    }

    private static ScannerProperties scannerProperties(long semanticTimeoutMs) {
        return new ScannerProperties(
                "AI_ORCHESTRATION",
                List.of(".java"),
                List.of(".git", "target"),
                512_000L,
                0.6,
                List.of("."),
                new ScannerProperties.Semantic(true, "local-qwen", "qwen3:8b", true,
                        List.of("local-qwen", "claude", "codex"), semanticTimeoutMs, 200, 12, 6000, 512,
                        "scanner-semantic-v1", 24,
                        com.mbworldwideapps.aiorchestration.modules.scanner.SemanticSelectionMode.ALL));
    }
}
