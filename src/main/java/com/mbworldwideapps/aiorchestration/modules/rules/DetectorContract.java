package com.mbworldwideapps.aiorchestration.modules.rules;

import java.util.Set;

/** Versioned, hash-bound runtime capabilities of one deterministic detector. */
public record DetectorContract(
        String contractId,
        Set<BindingKind> promotableBindingKinds,
        Set<BindingKind> requiredInputKinds,
        String contractHash) {

    public DetectorContract {
        if (contractId == null || contractId.isBlank()) {
            throw new IllegalArgumentException("detector contractId is required");
        }
        promotableBindingKinds = promotableBindingKinds == null ? Set.of() : Set.copyOf(promotableBindingKinds);
        requiredInputKinds = requiredInputKinds == null ? Set.of() : Set.copyOf(requiredInputKinds);
        if (promotableBindingKinds.isEmpty() || requiredInputKinds.isEmpty()) {
            throw new IllegalArgumentException("detector binding capabilities are required");
        }
        if (contractHash == null || !contractHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("detector contractHash must be a lowercase SHA-256 value");
        }
    }
}
