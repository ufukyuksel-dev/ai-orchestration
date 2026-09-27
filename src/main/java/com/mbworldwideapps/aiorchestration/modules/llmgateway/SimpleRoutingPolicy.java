package com.mbworldwideapps.aiorchestration.modules.llmgateway;

import com.mbworldwideapps.aiorchestration.config.AiOrchestrationProperties;
import org.springframework.stereotype.Component;

@Component
public class SimpleRoutingPolicy implements RoutingPolicy {

    private final AiOrchestrationProperties properties;

    public SimpleRoutingPolicy(AiOrchestrationProperties properties) {
        this.properties = properties;
    }

    @Override
    public String select(GenerationRequest request) {
        if (request.requestedProvider() != null && !request.requestedProvider().isBlank()) {
            return request.requestedProvider().trim();
        }
        return properties.generation().provider();
    }
}
