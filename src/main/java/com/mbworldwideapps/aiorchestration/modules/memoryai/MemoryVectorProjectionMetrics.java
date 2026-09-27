package com.mbworldwideapps.aiorchestration.modules.memoryai;

import java.time.Duration;
import java.util.Set;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

@Component
class MemoryVectorProjectionMetrics {

    private static final Set<String> OPERATIONS = Set.of(
            "upsert", "metadata_skip", "delete", "retry", "dead_letter", "queue_rejected", "superseded");

    private final MeterRegistry registry;

    MemoryVectorProjectionMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    void record(String operation) {
        String safe = OPERATIONS.contains(operation) ? operation : "other";
        Counter.builder("ai_orchestration_memory_projection_operations_total")
                .tag("operation", safe)
                .register(registry)
                .increment();
    }

    void latency(Duration duration, String outcome) {
        Timer.builder("ai_orchestration_memory_projection_latency")
                .tag("outcome", Set.of("success", "retry", "dead_letter", "superseded").contains(outcome)
                        ? outcome : "other")
                .register(registry)
                .record(duration);
    }
}
