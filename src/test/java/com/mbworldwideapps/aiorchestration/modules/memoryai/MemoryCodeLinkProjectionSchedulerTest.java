package com.mbworldwideapps.aiorchestration.modules.memoryai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.mbworldwideapps.aiorchestration.config.MemoryCodeLinkProperties;
import org.junit.jupiter.api.Test;

class MemoryCodeLinkProjectionSchedulerTest {

    @Test
    void queueRejectionDoesNotThrowAndIsVisible() throws Exception {
        MemoryCodeLinkProjectionService service = mock(MemoryCodeLinkProjectionService.class);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(service.linkAll(anyString())).thenAnswer(invocation -> {
            started.countDown();
            release.await(5, TimeUnit.SECONDS);
            return new MemoryCodeLinkProjectionResult(UUID.randomUUID(), invocation.getArgument(0), null,
                    false, true, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1L, "");
        });
        DefaultMemoryCodeLinkProjectionScheduler scheduler = new DefaultMemoryCodeLinkProjectionScheduler(
                properties(1), service);

        scheduler.linkAll("AI_ORCHESTRATION");
        assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
        scheduler.linkAll("AI_ORCHESTRATION");
        assertThatCode(() -> scheduler.linkAll("AI_ORCHESTRATION")).doesNotThrowAnyException();

        awaitState(scheduler, "rejected");
        assertThat(scheduler.status().droppedCount()).isEqualTo(1L);
        release.countDown();
        awaitState(scheduler, "completed");
        scheduler.shutdown();
    }

    private static void awaitState(DefaultMemoryCodeLinkProjectionScheduler scheduler, String state)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 2000L;
        while (System.currentTimeMillis() < deadline) {
            if (state.equals(scheduler.status().state())) {
                return;
            }
            Thread.sleep(20L);
        }
        assertThat(scheduler.status().state()).isEqualTo(state);
    }

    private static MemoryCodeLinkProperties properties(int queueCapacity) {
        return new MemoryCodeLinkProperties(true, true, false, 0.72, 5, 2, queueCapacity);
    }
}
