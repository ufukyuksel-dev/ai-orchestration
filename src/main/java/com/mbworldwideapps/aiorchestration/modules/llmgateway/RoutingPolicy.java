package com.mbworldwideapps.aiorchestration.modules.llmgateway;

public interface RoutingPolicy {

    String select(GenerationRequest request);
}
