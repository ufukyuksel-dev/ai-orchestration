package com.mbworldwideapps.aiorchestration.modules.memoryai;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class MemoryExceptionHandlerTest {

    @Test
    void projectionQueueFullIsNamedRetryableServiceUnavailable() {
        var response = new MemoryExceptionHandler()
                .projectionQueueFull(new MemoryProjectionQueueFullException(25));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).containsEntry("error", "projection_queue_full")
                .containsEntry("message", "MEMORY_PROJECTION_QUEUE_FULL: capacity=25")
                .containsEntry("retryable", "true");
    }
}
