package com.mbworldwideapps.aiorchestration.modules.llmgateway;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.Test;

class TimeBoxedLLMProviderTest {

    @Test
    void throwsTimeoutWhenDelegateExceedsLimit() {
        ExecutorService executorService = Executors.newSingleThreadExecutor();
        try {
            LLMProvider slowProvider = new LLMProvider() {
                @Override
                public String id() {
                    return "slow";
                }

                @Override
                public GenerationResponse generate(GenerationRequest request) {
                    try {
                        Thread.sleep(250L);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return new GenerationResponse("late", "slow", 250L, false, DegradedReason.NONE, 1, 1, 0, 0, 0L);
                }
            };
            TimeBoxedLLMProvider provider = new TimeBoxedLLMProvider(slowProvider, 10L, executorService);

            assertThatThrownBy(() -> provider.generate(new GenerationRequest("q", "u", List.of(), "fresh", "slow",
                    null, 0, "test")))
                    .isInstanceOf(LLMProviderTimeoutException.class)
                    .hasMessageContaining("slow after 10 ms");
        } finally {
            executorService.shutdownNow();
        }
    }

    @Test
    void usesRequestAwareTimeout() {
        ExecutorService executorService = Executors.newSingleThreadExecutor();
        try {
            LLMProvider slowProvider = new LLMProvider() {
                @Override
                public String id() {
                    return "slow";
                }

                @Override
                public GenerationResponse generate(GenerationRequest request) {
                    try {
                        Thread.sleep(80L);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return new GenerationResponse("late", "slow", 80L, false, DegradedReason.NONE, 1, 1, 0, 0, 0L);
                }
            };
            TimeBoxedLLMProvider provider = new TimeBoxedLLMProvider(slowProvider,
                    request -> "orchestrator".equals(request.role()) ? 10L : 500L,
                    executorService);

            assertThatThrownBy(() -> provider.generate(new GenerationRequest("q", "u", List.of(), "fresh", "slow",
                    null, 0, "orchestrator")))
                    .isInstanceOf(LLMProviderTimeoutException.class)
                    .hasMessageContaining("slow after 10 ms");
        } finally {
            executorService.shutdownNow();
        }
    }
}
