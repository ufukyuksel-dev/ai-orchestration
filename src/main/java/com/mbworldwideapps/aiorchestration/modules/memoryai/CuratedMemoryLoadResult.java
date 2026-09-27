package com.mbworldwideapps.aiorchestration.modules.memoryai;

import java.util.List;

public record CuratedMemoryLoadResult(
        int scanned,
        int loaded,
        int updated,
        int skipped,
        int rejected,
        List<CuratedMemoryFileResult> files) {

    static CuratedMemoryLoadResult empty() {
        return new CuratedMemoryLoadResult(0, 0, 0, 0, 0, List.of());
    }

    static CuratedMemoryLoadResult combine(List<CuratedMemoryLoadResult> results) {
        return new CuratedMemoryLoadResult(
                results.stream().mapToInt(CuratedMemoryLoadResult::scanned).sum(),
                results.stream().mapToInt(CuratedMemoryLoadResult::loaded).sum(),
                results.stream().mapToInt(CuratedMemoryLoadResult::updated).sum(),
                results.stream().mapToInt(CuratedMemoryLoadResult::skipped).sum(),
                results.stream().mapToInt(CuratedMemoryLoadResult::rejected).sum(),
                results.stream().flatMap(result -> result.files().stream()).toList());
    }
}
