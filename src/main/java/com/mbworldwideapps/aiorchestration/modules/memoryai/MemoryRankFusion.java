package com.mbworldwideapps.aiorchestration.modules.memoryai;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class MemoryRankFusion {

    private static final int RRF_K = 60;

    private MemoryRankFusion() {
    }

    static List<ScoredMemoryRef> fuse(List<List<ScoredMemoryRef>> lanes, int topK) {
        if (lanes == null || lanes.isEmpty() || topK <= 0) {
            return List.of();
        }
        int nonEmptyLanes = (int) lanes.stream().filter(lane -> lane != null && !lane.isEmpty()).count();
        if (nonEmptyLanes == 0) {
            return List.of();
        }
        Map<UUID, Candidate> candidates = new LinkedHashMap<>();
        for (List<ScoredMemoryRef> lane : lanes) {
            if (lane == null) {
                continue;
            }
            int rank = 0;
            java.util.HashSet<UUID> seenInLane = new java.util.HashSet<>();
            for (ScoredMemoryRef ref : lane) {
                if (ref == null || ref.item() == null || !seenInLane.add(ref.item().id())) {
                    continue;
                }
                rank++;
                Candidate candidate = candidates.computeIfAbsent(ref.item().id(), ignored -> new Candidate(ref.item()));
                candidate.rawScore += 1.0 / (RRF_K + rank);
                candidate.bestRank = Math.min(candidate.bestRank, rank);
            }
        }
        double maximum = nonEmptyLanes / (double) (RRF_K + 1);
        List<Candidate> ordered = new ArrayList<>(candidates.values());
        ordered.sort(Comparator.comparingDouble((Candidate candidate) -> candidate.rawScore).reversed()
                .thenComparingInt(candidate -> candidate.bestRank)
                .thenComparing(candidate -> candidate.item.id()));
        return ordered.stream()
                .limit(topK)
                .map(candidate -> new ScoredMemoryRef(candidate.item,
                        Math.min(1.0, candidate.rawScore / maximum),
                        ScoredMemoryRef.ScoreKind.FUSED_RANK))
                .toList();
    }

    private static final class Candidate {
        private final MemoryItem item;
        private double rawScore;
        private int bestRank = Integer.MAX_VALUE;

        private Candidate(MemoryItem item) {
            this.item = item;
        }
    }
}
