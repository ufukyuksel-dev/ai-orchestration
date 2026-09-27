package com.mbworldwideapps.aiorchestration.modules.llmgateway;

public interface LLMProvider {

    String id();

    GenerationResponse generate(GenerationRequest request);
}
