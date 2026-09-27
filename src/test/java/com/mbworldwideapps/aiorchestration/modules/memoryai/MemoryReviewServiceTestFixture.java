package com.mbworldwideapps.aiorchestration.modules.memoryai;

import java.util.UUID;
import java.util.function.Predicate;

import com.mbworldwideapps.aiorchestration.core.pii.PiiScrubber;
import com.mbworldwideapps.aiorchestration.core.policy.PolicyEngine;
import org.springframework.context.ApplicationEventPublisher;

/** Test-only construction helpers; production wiring has no fail-open rule-policy path. */
public final class MemoryReviewServiceTestFixture {

    private MemoryReviewServiceTestFixture() {
    }

    public static MemoryReviewService create(MemoryRepository repository, MemoryVectorIndex vectorIndex,
            ApplicationEventPublisher publisher) {
        return create(repository, vectorIndex, publisher, PolicyEngine.allowAll(), new PiiScrubber());
    }

    public static MemoryReviewService create(MemoryRepository repository, MemoryVectorIndex vectorIndex,
            ApplicationEventPublisher publisher, RuleMemoryActivationPolicy rulePolicy) {
        return create(repository, vectorIndex, publisher, PolicyEngine.allowAll(), new PiiScrubber(),
                rulePolicy, ignored -> false);
    }

    public static MemoryReviewService create(MemoryRepository repository, MemoryVectorIndex vectorIndex,
            ApplicationEventPublisher publisher, PolicyEngine policyEngine, PiiScrubber piiScrubber) {
        return create(repository, vectorIndex, publisher, policyEngine, piiScrubber,
                MemoryServiceTestFixture.rules(false), ignored -> false);
    }

    public static MemoryReviewService create(MemoryRepository repository, MemoryVectorIndex vectorIndex,
            ApplicationEventPublisher publisher, PolicyEngine policyEngine, PiiScrubber piiScrubber,
            RuleMemoryActivationPolicy rulePolicy, Predicate<UUID> linkedRuleMemory) {
        return new MemoryReviewService(repository, vectorIndex, publisher, policyEngine, piiScrubber,
                rulePolicy, linkedRuleMemory::test);
    }
}
