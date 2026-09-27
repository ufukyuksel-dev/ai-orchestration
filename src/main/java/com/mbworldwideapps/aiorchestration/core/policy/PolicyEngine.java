package com.mbworldwideapps.aiorchestration.core.policy;

import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryScope;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemorySourceType;

public interface PolicyEngine {

    PolicyDecision evaluateMemoryWrite(MemorySourceType sourceType, MemoryScope scope);

    PolicyDecision evaluateMemoryContent(String text);

    static PolicyEngine allowAll() {
        return new PolicyEngine() {
            @Override
            public PolicyDecision evaluateMemoryWrite(MemorySourceType sourceType, MemoryScope scope) {
                return PolicyDecision.allow();
            }

            @Override
            public PolicyDecision evaluateMemoryContent(String text) {
                return PolicyDecision.allow();
            }
        };
    }
}
