package com.mbworldwideapps.aiorchestration.modules.agentlearning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mbworldwideapps.aiorchestration.modules.agentlearning.AgentLearningModels.LearningBatch;
import com.mbworldwideapps.aiorchestration.modules.agentlearning.AgentLearningModels.LearningReference;
import com.mbworldwideapps.aiorchestration.modules.agentlearning.AgentLearningModels.SemanticCandidate;
import org.junit.jupiter.api.Test;

class AgentLearningContractTest {

    private final AgentLearningContract contract = new AgentLearningContract(new ObjectMapper());

    @Test
    void acceptsCanonicalSemanticPayload() {
        LearningBatch batch = new LearningBatch(List.of(candidate("t1", "t2", "t3")));
        assertThat(contract.validate(batch)).isSameAs(batch);
    }

    @Test
    void acceptsEmptyBatchWithoutInventingAWrite() {
        LearningBatch batch = new LearningBatch(List.of());
        assertThat(contract.validate(batch)).isSameAs(batch);
    }

    @Test
    void rejectsUnknownAndDuplicateRefs() {
        assertThatThrownBy(() -> contract.validate(new LearningBatch(List.of(candidate("payment", "t2", "t3")))))
                .hasMessageContaining("anchors");
        assertThatThrownBy(() -> contract.validate(new LearningBatch(List.of(candidate("t1", "t1", "t1")))))
                .hasMessageContaining("evidence");
    }

    private static SemanticCandidate candidate(String anchor, String evidence, String secondEvidence) {
        return new SemanticCandidate("behavior", "Fallback route", "The secondary route is conditional.",
                List.of(anchor), List.of(evidence, secondEvidence), List.of("QR flow"), List.of(), List.of("routing"),
                null, new LearningReference("# Investigation\nSupporting call chain."));
    }
}
