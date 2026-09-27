package com.mbworldwideapps.aiorchestration.eval.memory;

import java.util.Set;

import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryScope;

public enum MemoryEvalVariant {
    V0(Set.of()),
    V1(Set.of(MemoryScope.GLOBAL)),
    V2(Set.of(MemoryScope.GLOBAL, MemoryScope.PROJECT)),
    V3(Set.of(MemoryScope.GLOBAL, MemoryScope.PROJECT, MemoryScope.EPISODIC));

    private final Set<MemoryScope> scopes;

    MemoryEvalVariant(Set<MemoryScope> scopes) {
        this.scopes = scopes;
    }

    public boolean includes(MemoryScope scope) {
        return scopes.contains(scope);
    }
}
