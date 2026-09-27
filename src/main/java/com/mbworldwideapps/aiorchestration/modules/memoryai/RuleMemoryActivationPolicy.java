package com.mbworldwideapps.aiorchestration.modules.memoryai;

import java.util.Objects;

import com.mbworldwideapps.aiorchestration.config.RulesProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Central chokepoint for RULE-type memory retrieval eligibility. When the rule
 * authority is enabled, no memory write path may move a RULE memory into ACTIVE
 * or STALE; the only
 * legitimate transition happens inside the promotion transaction through
 * {@link RuleMemoryPromotionPort}, which verifies database evidence instead of
 * trusting its caller. Pre-existing ACTIVE rule memories (legacy: active RULE
 * without a linked rule definition) are left untouched; the policy only blocks
 * new activations.
 */
@Component
public class RuleMemoryActivationPolicy {

    public static final String GUIDANCE =
            "RULE memories are activated via rules.promote, not through memory write paths";

    private final boolean enabled;

    @Autowired
    public RuleMemoryActivationPolicy(RulesProperties properties) {
        this.enabled = Objects.requireNonNull(properties, "properties").enabled();
    }

    private RuleMemoryActivationPolicy(boolean enabled) {
        this.enabled = enabled;
    }

    /** Fail-safe default for constructors that are not Spring-wired: authority on. */
    public static RuleMemoryActivationPolicy authorityEnabled() {
        return new RuleMemoryActivationPolicy(true);
    }

    public boolean enabled() {
        return enabled;
    }

    public boolean blocksActivation(MemoryType type, MemoryStatus currentStatus, MemoryStatus targetStatus) {
        return enabled
                && type == MemoryType.RULE
                && retrievalEligible(targetStatus)
                && !retrievalEligible(currentStatus);
    }

    public void assertActivationAllowed(MemoryType type, MemoryStatus currentStatus, MemoryStatus targetStatus) {
        if (blocksActivation(type, currentStatus, targetStatus)) {
            throw new IllegalArgumentException(GUIDANCE);
        }
    }

    /**
     * Curation and curated-load paths do not fail; they downgrade a would-be
     * ACTIVE RULE to PENDING_REVIEW so it becomes a promotion candidate.
     */
    public MemoryStatus coerceCandidateStatus(MemoryType type, MemoryStatus requested) {
        if (enabled && type == MemoryType.RULE && retrievalEligible(requested)) {
            return MemoryStatus.PENDING_REVIEW;
        }
        return requested;
    }

    private static boolean retrievalEligible(MemoryStatus status) {
        return status == MemoryStatus.ACTIVE || status == MemoryStatus.STALE;
    }
}
