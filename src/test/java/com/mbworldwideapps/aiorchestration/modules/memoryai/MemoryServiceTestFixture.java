package com.mbworldwideapps.aiorchestration.modules.memoryai;

import java.util.List;

import com.mbworldwideapps.aiorchestration.config.AiOrchestrationProperties;
import com.mbworldwideapps.aiorchestration.config.RulesProperties;
import com.mbworldwideapps.aiorchestration.core.pii.PiiScrubber;
import com.mbworldwideapps.aiorchestration.core.policy.PolicyEngine;
import org.springframework.context.ApplicationEventPublisher;

/** Explicit test-only wiring; production MemoryService has no fail-open constructor. */
public final class MemoryServiceTestFixture {

    private static final RuleMemoryLinkLookup NO_LINKED_RULES = ignored -> false;
    private static final MemoryVectorIndex NO_OP_VECTOR_INDEX = new MemoryVectorIndex() {
        @Override
        public void upsert(MemoryItem item) {
        }

        @Override
        public void delete(MemoryItem item) {
        }

        @Override
        public List<ScoredMemoryRef> search(String query, int topK, MemoryEligibilityFilter filter) {
            return List.of();
        }
    };

    private MemoryServiceTestFixture() {
    }

    public static MemoryService create(MemoryRepository repository, AiOrchestrationProperties properties) {
        return create(repository, properties, event -> {
        }, PolicyEngine.allowAll(), new PiiScrubber(), NO_OP_VECTOR_INDEX, rules(false), NO_LINKED_RULES);
    }

    public static MemoryService create(MemoryRepository repository, AiOrchestrationProperties properties,
            ApplicationEventPublisher publisher) {
        return create(repository, properties, publisher, PolicyEngine.allowAll(), new PiiScrubber(),
                NO_OP_VECTOR_INDEX, rules(false), NO_LINKED_RULES);
    }

    public static MemoryService create(MemoryRepository repository, AiOrchestrationProperties properties,
            ApplicationEventPublisher publisher, PolicyEngine policyEngine) {
        return create(repository, properties, publisher, policyEngine, new PiiScrubber(),
                NO_OP_VECTOR_INDEX, rules(false), NO_LINKED_RULES);
    }

    public static MemoryService create(MemoryRepository repository, AiOrchestrationProperties properties,
            ApplicationEventPublisher publisher, PolicyEngine policyEngine, PiiScrubber piiScrubber) {
        return create(repository, properties, publisher, policyEngine, piiScrubber,
                NO_OP_VECTOR_INDEX, rules(false), NO_LINKED_RULES);
    }

    public static MemoryService create(MemoryRepository repository, AiOrchestrationProperties properties,
            ApplicationEventPublisher publisher, PolicyEngine policyEngine, PiiScrubber piiScrubber,
            MemoryVectorIndex vectorIndex) {
        return create(repository, properties, publisher, policyEngine, piiScrubber, vectorIndex,
                rules(false), NO_LINKED_RULES);
    }

    public static MemoryService create(MemoryRepository repository, AiOrchestrationProperties properties,
            ApplicationEventPublisher publisher, PolicyEngine policyEngine, PiiScrubber piiScrubber,
            MemoryVectorIndex vectorIndex, RuleMemoryActivationPolicy rulePolicy) {
        return create(repository, properties, publisher, policyEngine, piiScrubber, vectorIndex,
                rulePolicy, NO_LINKED_RULES);
    }

    public static MemoryService create(MemoryRepository repository, AiOrchestrationProperties properties,
            ApplicationEventPublisher publisher, PolicyEngine policyEngine, PiiScrubber piiScrubber,
            MemoryVectorIndex vectorIndex, RuleMemoryActivationPolicy rulePolicy,
            RuleMemoryLinkLookup linkLookup) {
        return new MemoryService(repository, properties, publisher, policyEngine, piiScrubber,
                vectorIndex == null ? NO_OP_VECTOR_INDEX : vectorIndex, rulePolicy, linkLookup);
    }

    public static RuleMemoryActivationPolicy rules(boolean enabled) {
        return new RuleMemoryActivationPolicy(new RulesProperties(enabled, 20, 256, 4096, 200));
    }

    public static MemoryVectorIndex noOpVectorIndex() {
        return NO_OP_VECTOR_INDEX;
    }
}
