package com.mbworldwideapps.aiorchestration.modules.memoryai;

import com.mbworldwideapps.aiorchestration.config.AiOrchestrationProperties;

/** Test-only construction helpers; production retrieval has one strict constructor. */
public final class MemoryRetrievalServiceTestFixture {

    private MemoryRetrievalServiceTestFixture() {
    }

    public static MemoryRetrievalService create(MemoryRepository repository, MemoryVectorIndex vectorIndex,
            AiOrchestrationProperties properties) {
        return new MemoryRetrievalService(repository, vectorIndex, properties);
    }

    public static MemoryRetrievalService fixedResponse(MemoryContextResponse response) {
        return new MemoryRetrievalService(null, null, null) {
            @Override
            public MemoryContextResponse retrieve(String query, String projectKey) {
                return response;
            }

            @Override
            public MemoryContextResponse retrieve(String query, String projectKey, String userId) {
                return response;
            }
        };
    }
}
