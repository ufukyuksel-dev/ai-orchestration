package com.mbworldwideapps.aiorchestration.modules.llmgateway;

import java.time.Duration;
import java.time.Instant;

import com.mbworldwideapps.aiorchestration.core.telemetry.AiMetrics;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class DefaultLLMGateway implements LLMGateway {

    private final RoutingPolicy routingPolicy;
    private final ProviderRegistry providerRegistry;
    private final AiMetrics aiMetrics;

    @Autowired
    public DefaultLLMGateway(RoutingPolicy routingPolicy, ProviderRegistry providerRegistry, AiMetrics aiMetrics) {
        this.routingPolicy = routingPolicy;
        this.providerRegistry = providerRegistry;
        this.aiMetrics = aiMetrics;
    }

    DefaultLLMGateway(RoutingPolicy routingPolicy, ProviderRegistry providerRegistry) {
        this(routingPolicy, providerRegistry, null);
    }

    @Override
    public GenerationResponse generate(GenerationRequest request) {
        Instant started = Instant.now();
        String providerId = routingPolicy.select(request);
        try {
            GenerationResponse response = providerRegistry.get(providerId).generate(request);
            record(providerId, request, "success", response.degraded(), response.latencyMs());
            return response;
        } catch (LLMProviderTimeoutException e) {
            record(e.providerId(), request, "timeout", true, Duration.between(started, Instant.now()).toMillis());
            throw e;
        } catch (RuntimeException e) {
            record(providerId, request, "error", true, Duration.between(started, Instant.now()).toMillis());
            throw e;
        }
    }

    private void record(String providerId, GenerationRequest request, String outcome, boolean degraded, long latencyMs) {
        if (aiMetrics != null) {
            aiMetrics.recordGenerationCall(providerId, request.role(), outcome, degraded, latencyMs);
        }
    }
}
