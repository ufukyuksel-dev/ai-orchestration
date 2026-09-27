package com.mbworldwideapps.aiorchestration.core.diff;

import com.mbworldwideapps.aiorchestration.core.diff.DiffHunk;
import java.util.List;

public record ParsedFile(
        String path,
        List<DiffHunk> hunks) {

    public ParsedFile {
        hunks = hunks == null ? List.of() : List.copyOf(hunks);
    }
}
