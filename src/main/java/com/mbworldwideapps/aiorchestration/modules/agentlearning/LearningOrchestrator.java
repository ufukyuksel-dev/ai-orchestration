package com.mbworldwideapps.aiorchestration.modules.agentlearning;

import com.mbworldwideapps.aiorchestration.modules.agentlearning.AgentLearningModels.CompactLearnReceipt;
import com.mbworldwideapps.aiorchestration.modules.agentlearning.AgentLearningModels.LearningCaptureCommand;
import com.mbworldwideapps.aiorchestration.modules.agentlearning.AgentLearningModels.LearningBatch;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * The single compact producer boundary for reusable agent learning.
 *
 * <p>The domain service owns the transactional stages; callers submit one
 * semantic batch here instead of coordinating memory, evidence, references,
 * and relations as separate persistence calls.</p>
 */
@Service
public final class LearningOrchestrator {

    private final AgentLearningService learning;

    public LearningOrchestrator(AgentLearningService learning) {
        this.learning = learning;
    }

    public CompactLearnReceipt learn(String principalKey, String clientId, String sessionScopeHash,
            String projectKey, LearningBatch batch, String producerRuntime, String producerModel) {
        return learning.learnSemantic(principalKey, clientId, sessionScopeHash, projectKey,
                batch, producerRuntime, producerModel);
    }

    public CompactLearnReceipt capture(String principalKey, String clientId, String sessionScopeHash,
            LearningCaptureCommand command, String producerRuntime, String producerModel) {
        return learning.captureSemantic(principalKey, clientId, sessionScopeHash, command.projectKey(),
                UUID.fromString(command.workspaceBindingId()), command.taskRunId(),
                command.learningCandidates(), producerRuntime, producerModel);
    }
}
