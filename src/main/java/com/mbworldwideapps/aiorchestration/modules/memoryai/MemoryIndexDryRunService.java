package com.mbworldwideapps.aiorchestration.modules.memoryai;

import com.mbworldwideapps.aiorchestration.config.AiOrchestrationProperties;
import org.springframework.stereotype.Service;

@Service
public class MemoryIndexDryRunService {

    private final MemoryRepository repository;
    private final MemoryEmbeddingContract contract;
    private final AiOrchestrationProperties properties;

    public MemoryIndexDryRunService(MemoryRepository repository, MemoryEmbeddingContract contract,
            AiOrchestrationProperties properties) {
        this.repository = repository;
        this.contract = contract;
        this.properties = properties;
    }

    public ReindexDryRun inspect() {
        MemoryEmbeddingProperties embedding = contract.properties();
        long indexable = repository.countIndexable();
        boolean shadowConfigured = embedding.shadowMode() != MemoryShadowMode.OFF;
        return new ReindexDryRun(
                properties.memory().episodicCollectionName(),
                contract.activeFingerprint().value(),
                contract.activeCompatibility(),
                shadowConfigured ? embedding.shadowCollectionName() : "",
                embedding.shadowExpectedIndexFingerprint(),
                embedding.shadowMode(),
                indexable,
                shadowConfigured ? indexable : 0L,
                0L,
                false);
    }

    public record ReindexDryRun(
            String activeCollection,
            String activeFingerprint,
            MemoryEmbeddingContract.Compatibility activeCompatibility,
            String shadowCollection,
            String expectedShadowFingerprint,
            MemoryShadowMode shadowMode,
            long indexableRows,
            long estimatedEmbeddingCalls,
            long writesPerformed,
            boolean executed) {
    }
}
