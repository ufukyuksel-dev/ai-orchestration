package com.mbworldwideapps.aiorchestration.modules.memoryai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.mbworldwideapps.aiorchestration.config.AiOrchestrationProperties;
import com.mbworldwideapps.aiorchestration.core.embedding.HashingEmbeddingModel;
import org.junit.jupiter.api.Test;

class MemoryIndexDryRunServiceTest {

    @Test
    void dryRunCountsWorkWithoutPerformingWrites() {
        var model = new HashingEmbeddingModel(64);
        String activeExpected = MemoryEmbeddingFingerprint.create(model, "internal", "hashing-a", "r1",
                "nfkc-v1", "legacy-v1").value();
        String shadowExpected = MemoryEmbeddingFingerprint.create(model, "internal", "hashing-a", "r1",
                "nfkc-v1", "discovery-v2").value();
        MemoryEmbeddingProperties embedding = MemoryEmbeddingContractTest.properties(activeExpected,
                "legacy-v1", MemoryShadowMode.WRITE_ONLY, shadowExpected);
        MemoryEmbeddingContract contract = new MemoryEmbeddingContract(model, embedding);
        MemoryRepository repository = mock(MemoryRepository.class);
        when(repository.countIndexable()).thenReturn(37L);
        AiOrchestrationProperties properties = mock(AiOrchestrationProperties.class);
        AiOrchestrationProperties.Memory memory = mock(AiOrchestrationProperties.Memory.class);
        when(properties.memory()).thenReturn(memory);
        when(memory.episodicCollectionName()).thenReturn("memory_active");

        MemoryIndexDryRunService.ReindexDryRun result =
                new MemoryIndexDryRunService(repository, contract, properties).inspect();

        assertThat(result.activeCollection()).isEqualTo("memory_active");
        assertThat(result.shadowCollection()).isEqualTo("memory_shadow");
        assertThat(result.indexableRows()).isEqualTo(37L);
        assertThat(result.estimatedEmbeddingCalls()).isEqualTo(37L);
        assertThat(result.writesPerformed()).isZero();
        assertThat(result.executed()).isFalse();
    }
}
