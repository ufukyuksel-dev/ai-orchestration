package com.mbworldwideapps.aiorchestration.core.diff;

public record DiffHunk(
        int oldStart,
        int oldLines,
        int newStart,
        int newLines) {
}
