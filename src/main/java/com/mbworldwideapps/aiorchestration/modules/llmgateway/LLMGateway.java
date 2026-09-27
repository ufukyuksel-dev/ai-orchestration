package com.mbworldwideapps.aiorchestration.modules.llmgateway;

public interface LLMGateway {

    GenerationResponse generate(GenerationRequest request);
}
