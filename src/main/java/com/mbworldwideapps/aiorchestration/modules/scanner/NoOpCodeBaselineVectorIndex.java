package com.mbworldwideapps.aiorchestration.modules.scanner;

import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "ai-orchestration.scanner.vector-index", name = "enabled", havingValue = "false")
public class NoOpCodeBaselineVectorIndex implements CodeBaselineVectorIndex {

    @Override
    public void upsert(CodeSemanticCapsuleRecord capsule) {
    }

    @Override
    public List<ScoredCodeCapsuleRef> search(String query, int topK, String projectKey) {
        return List.of();
    }
}
