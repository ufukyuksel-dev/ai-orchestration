package com.mbworldwideapps.aiorchestration.modules.memoryai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mbworldwideapps.aiorchestration.core.embedding.HashingEmbeddingModel;
import com.mbworldwideapps.aiorchestration.config.AiOrchestrationProperties;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MemoryEmbeddingContractTest {

    @Test
    void reportsRuntimeIdentityWithoutTreatingCollectionAsModelIdentity() {
        EmbeddingModel model = new HashingEmbeddingModel(64);
        MemoryEmbeddingProperties properties = properties("", "legacy-v1", MemoryShadowMode.OFF, "");

        MemoryEmbeddingContract contract = new MemoryEmbeddingContract(model, properties);

        assertThat(contract.activeFingerprint().runtimeModelClass()).isEqualTo(model.getClass().getName());
        assertThat(contract.activeFingerprint().modelIdentity()).isEqualTo("hashing-a");
        assertThat(contract.activeFingerprint().dimensions()).isEqualTo(64);
        assertThat(contract.activeCompatibility()).isEqualTo(MemoryEmbeddingContract.Compatibility.UNVERIFIED);
        assertThat(contract.activeFingerprint().value()).doesNotContain("memory_collection");
    }

    @Test
    void sameDimensionDifferentRuntimeModelProducesMismatch() {
        EmbeddingModel indexedModel = new HashingEmbeddingModel(64);
        String expected = MemoryEmbeddingFingerprint.active(indexedModel,
                properties("", "legacy-v1", MemoryShadowMode.OFF, "")).value();
        EmbeddingModel runtimeModel = new AlternateHashingEmbeddingModel(64);
        MemoryEmbeddingContract contract = new MemoryEmbeddingContract(runtimeModel,
                properties(expected, "legacy-v1", MemoryShadowMode.OFF, ""));

        assertThat(contract.activeCompatibility()).isEqualTo(MemoryEmbeddingContract.Compatibility.MISMATCH);
        assertThatThrownBy(contract::assertActiveCompatible)
                .isInstanceOf(MemoryEmbeddingMismatchException.class)
                .hasMessageContaining("EMBEDDING_MISMATCH", "lane=active");
    }

    @Test
    void retrievalTextSchemaChangeProducesMismatch() {
        EmbeddingModel model = new HashingEmbeddingModel(64);
        String expected = MemoryEmbeddingFingerprint.active(model,
                properties("", "legacy-v1", MemoryShadowMode.OFF, "")).value();
        MemoryEmbeddingContract contract = new MemoryEmbeddingContract(model,
                properties(expected, "discovery-v2", MemoryShadowMode.OFF, ""));

        assertThat(contract.activeCompatibility()).isEqualTo(MemoryEmbeddingContract.Compatibility.MISMATCH);
        assertThatThrownBy(contract::assertActiveCompatible)
                .isInstanceOf(MemoryEmbeddingMismatchException.class);
    }

    @Test
    void healthSeparatesRuntimeIdentityFingerprintAndCollectionName() {
        EmbeddingModel model = new HashingEmbeddingModel(64);
        MemoryEmbeddingContract contract = new MemoryEmbeddingContract(model,
                properties("", "legacy-v1", MemoryShadowMode.OFF, ""));
        AiOrchestrationProperties root = mock(AiOrchestrationProperties.class);
        AiOrchestrationProperties.Memory memory = mock(AiOrchestrationProperties.Memory.class);
        when(root.memory()).thenReturn(memory);
        when(memory.episodicCollectionName()).thenReturn("misleading_bge_name");

        var health = new MemoryEmbeddingHealthIndicator(contract, root).health();

        assertThat(health.getDetails())
                .containsEntry("compatibility", "UNVERIFIED")
                .containsEntry("runtimeModelClass", model.getClass().getName())
                .containsEntry("modelIdentity", "hashing-a")
                .containsEntry("dimensions", 64)
                .containsEntry("collection", "misleading_bge_name");
        assertThat(health.getDetails().get("indexFingerprint"))
                .isNotEqualTo(health.getDetails().get("collection"));
    }

    static MemoryEmbeddingProperties properties(String expected, String schema, MemoryShadowMode mode,
            String shadowExpected) {
        return new MemoryEmbeddingProperties("internal", "hashing-a", "r1", "nfkc-v1", schema, expected,
                mode, mode == MemoryShadowMode.OFF ? "" : "memory_shadow", "internal", "hashing-a", "r1",
                "nfkc-v1", "discovery-v2", shadowExpected);
    }

    private static final class AlternateHashingEmbeddingModel extends HashingEmbeddingModel {
        private AlternateHashingEmbeddingModel(int dimensions) {
            super(dimensions);
        }
    }
}
