package com.mbworldwideapps.aiorchestration.modules.agentlearning;

import java.time.Instant;

import com.mbworldwideapps.aiorchestration.config.AgentLearningProperties;
import com.mbworldwideapps.aiorchestration.modules.agentlearning.AgentLearningModels.PurgeResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(prefix = "ai-orchestration.learning", name = "enabled", havingValue = "true")
public class AgentLearningCleanupService {

    private final AgentLearningRepository repository;
    private final AgentLearningProperties properties;

    public AgentLearningCleanupService(AgentLearningRepository repository, AgentLearningProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${ai-orchestration.learning.cleanup-interval-ms:3600000}")
    @Transactional
    public void scheduledPurge() {
        purgeExpired(Instant.now());
    }

    public PurgeResult purgeExpired(Instant now) {
        return repository.purgeExpired(now, properties.cleanupBatchSize());
    }
}
