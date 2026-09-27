package com.mbworldwideapps.aiorchestration.modules.graph;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.mbworldwideapps.aiorchestration.modules.graph.GraphContextRetrieveResponse.GraphPath;

final class GraphContextPathSelector {

    private GraphContextPathSelector() {
    }

    static List<GraphPath> selectWithMemoryReservation(List<GraphPath> candidates, int limit,
            boolean memoryFirst) {
        return selectWithReservations(candidates, limit, memoryFirst, Set.of());
    }

    static List<GraphPath> selectWithReservations(List<GraphPath> candidates, int limit,
            boolean memoryFirst, Set<String> lexicalSeedKeys) {
        if (candidates == null || candidates.isEmpty() || limit <= 0) {
            return List.of();
        }
        List<GraphPath> ranked = candidates.stream()
                .sorted(Comparator.comparingDouble(GraphPath::score).reversed()
                        .thenComparing(GraphPath::pathId))
                .toList();
        GraphPath bestMemoryPath = ranked.stream()
                .filter(GraphContextPathSelector::isMemorySeeded)
                .findFirst()
                .orElse(null);
        Set<String> lexicalKeys = lexicalSeedKeys == null ? Set.of() : lexicalSeedKeys;
        GraphPath bestLexicalPath = ranked.stream()
                .filter(path -> lexicalKeys.contains(path.seedKey()))
                .findFirst()
                .orElse(null);

        List<GraphPath> selected = new ArrayList<>(Math.min(limit, ranked.size()));
        Set<String> selectedIds = new LinkedHashSet<>();
        if (memoryFirst) {
            addIfRoom(selected, selectedIds, bestMemoryPath, limit);
            addIfRoom(selected, selectedIds, bestLexicalPath, limit);
        } else {
            addIfRoom(selected, selectedIds, bestLexicalPath, limit);
            addIfRoom(selected, selectedIds, bestMemoryPath, limit);
        }
        for (GraphPath path : ranked) {
            if (selected.size() >= limit) {
                break;
            }
            if (selectedIds.add(path.pathId())) {
                selected.add(path);
            }
        }
        if (!memoryFirst) {
            selected.sort(Comparator.comparingDouble(GraphPath::score).reversed()
                    .thenComparing(GraphPath::pathId));
        }
        return List.copyOf(selected);
    }

    static Set<String> lexicalSeedKeys(List<GraphContextSeed> seeds) {
        if (seeds == null || seeds.isEmpty()) {
            return Set.of();
        }
        return seeds.stream()
                .filter(GraphContextSeed::isPostgresLexical)
                .map(GraphContextSeed::key)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static void addIfRoom(List<GraphPath> selected, Set<String> selectedIds,
            GraphPath path, int limit) {
        if (path != null && selected.size() < limit && selectedIds.add(path.pathId())) {
            selected.add(path);
        }
    }

    private static boolean isMemorySeeded(GraphPath path) {
        return GraphContextSeedKind.MEMORY.name().equals(path.seedKind());
    }
}
