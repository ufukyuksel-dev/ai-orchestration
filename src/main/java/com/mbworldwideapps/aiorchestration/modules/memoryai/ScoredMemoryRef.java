package com.mbworldwideapps.aiorchestration.modules.memoryai;

public record ScoredMemoryRef(MemoryItem item, double similarityScore, ScoreKind scoreKind) {

    public ScoredMemoryRef(MemoryItem item, double similarityScore) {
        this(item, similarityScore, ScoreKind.SIMILARITY);
    }

    public boolean passedLaneRelevanceGate() {
        return scoreKind == ScoreKind.FUSED_RANK;
    }

    public enum ScoreKind {
        SIMILARITY,
        FUSED_RANK
    }
}
