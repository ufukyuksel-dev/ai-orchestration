package com.mbworldwideapps.aiorchestration.modules.llmgateway;

import java.util.Set;

public interface ProviderRegistry {

    LLMProvider get(String providerId);

    Set<String> providerIds();
}
