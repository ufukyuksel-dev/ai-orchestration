package com.mbworldwideapps.aiorchestration.modules.rules;

/**
 * A single detector hit. Carries hashes instead of raw matched code so findings
 * can be logged and shipped without leaking source content.
 */
public record DetectorFinding(
        String targetHint,
        String excerptHash,
        String message,
        String findingHash) {
}
